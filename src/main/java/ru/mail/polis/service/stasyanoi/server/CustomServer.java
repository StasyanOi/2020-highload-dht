package ru.mail.polis.service.stasyanoi.server;

import one.nio.http.HttpException;
import one.nio.http.HttpServerConfig;
import one.nio.http.HttpSession;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.Request;
import one.nio.http.RequestMethod;
import one.nio.http.Response;
import one.nio.pool.PoolException;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Mapper;
import ru.mail.polis.service.stasyanoi.server.helpers.AckFrom;
import ru.mail.polis.service.stasyanoi.server.internal.BaseFunctionalityServer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

public class CustomServer extends BaseFunctionalityServer {

    /**
     * Custom server.
     *
     * @param dao      - DAO to use.
     * @param config   - config for server.
     * @param topology - topology of services.
     * @throws IOException - if an IO exception occurs.
     */
    public CustomServer(final DAO dao, final HttpServerConfig config, final Set<String> topology) throws IOException {
        super(dao, config, topology);
    }

    /**
     * Get a record by key.
     *
     * @param idParam - key.
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_GET)
    public void get(final @Param("id") String idParam, final HttpSession session, final Request request) {
        try {
            executorService.execute(() -> internalRun(idParam, session,
                    () -> getResponseFromLocalAndReplicas(idParam, request)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    /**
     * Endpoint for get replication.
     *
     * @param idParam - key.
     * @param session - session for the request.
     */
    @Path("/v0/entity/rep")
    @RequestMethod(Request.METHOD_GET)
    public void getReplication(final @Param("id") String idParam, final HttpSession session) {
        try {
            executorService.execute(() -> internalRun(idParam, session, () -> getResponseFromLocalNode(idParam)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    private Response getResponseFromLocalAndReplicas(final String idParam, final Request request) {
        final Response responseHttp;
        final int node = util.getNode(idParam, nodeAmount);
        final Response responseHttpTemp;
        if (node == thisNodeIndex) {
            responseHttpTemp = getResponseFromLocalNode(idParam);
        } else {
            responseHttpTemp = routeRequestToRemoteNode(request, node, nodeIndexToUrlMapping);
        }
        if (request.getParameter(SHOULD_REPLICATE, TRUE).equals(TRUE)) {
            responseHttp = replicateGet(request, node, responseHttpTemp);
        } else {
            responseHttp = responseHttpTemp;
        }
        return responseHttp;
    }

    private Response replicateGet(final Request request, final int node, final Response responseHttpTemp) {
        final Response responseHttp;
        final AckFrom ackFrom = util.getRF(request.getParameter(REPLICAS), nodeIndexToUrlMapping.size());
        final List<Response> responses = getReplicaResponses(request, node, ackFrom.getFrom() - 1);
        responses.add(responseHttpTemp);
        responseHttp = merger.mergeGetResponses(responses, ackFrom.getAck(), ackFrom.getFrom());
        return responseHttp;
    }

    private Response getResponseFromLocalNode(final String idParam) {
        final ByteBuffer id = Mapper.fromBytes(idParam.getBytes(StandardCharsets.UTF_8));
        try {
            final ByteBuffer body = dao.get(id);
            return util.getResponseWithTimestamp(body);
        } catch (NoSuchElementException | IOException e) {
            final byte[] deleteTime = dao.getDeleteTime(id);
            return util.getDeleteOrNotFoundResponse(deleteTime);
        }
    }

    /**
     * Create or update a record.
     *
     * @param idParam - key of the record.
     * @param request - request with the body.
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_PUT)
    public void put(final @Param("id") String idParam, final Request request, final HttpSession session) {
        try {
            executorService.execute(() -> internalRun(idParam, session,
                    () -> putResponseFromLocalAndReplicas(idParam, request)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    /**
     * Put replication.
     *
     * @param idParam - key.
     * @param request - out request.
     * @param session - session.
     */
    @Path("/v0/entity/rep")
    @RequestMethod(Request.METHOD_PUT)
    public void putReplication(final @Param("id") String idParam, final Request request, final HttpSession session) {
        try {
            executorService.execute(() -> internalRun(idParam, session, () -> putIntoLocalNode(request, idParam)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    private Response putResponseFromLocalAndReplicas(final String idParam, final Request request) {
        Response responseHttp;
        final int node = util.getNode(idParam, nodeAmount);
        Response responseHttpTemp;
        if (node == thisNodeIndex) {
            responseHttpTemp = putIntoLocalNode(request, idParam);
        } else {
            responseHttpTemp = routeRequestToRemoteNode(request, node, nodeIndexToUrlMapping);
        }
        if (request.getParameter(SHOULD_REPLICATE, TRUE).equals(TRUE)) {
            responseHttp = replicatePutOrDelete(request, node, responseHttpTemp, 201);
        } else {
            responseHttp = responseHttpTemp;
        }
        return responseHttp;
    }

    private Response replicatePutOrDelete(final Request request, final int node, final Response responseHttpTemp,
                                          final int goodStatus) {
        Response responseHttp;
        final AckFrom ackFrom = util.getRF(request.getParameter(REPLICAS), nodeIndexToUrlMapping.size());
        final List<Response> responses = getReplicaResponses(request, node, ackFrom.getFrom() - 1);
        responses.add(responseHttpTemp);
        responseHttp = merger.mergePutDeleteResponses(responses, ackFrom.getAck(), goodStatus, ackFrom.getFrom());
        return responseHttp;
    }

    @NotNull
    private List<Response> getReplicaResponses(final Request request, final int node, final int fromOtherReplicas) {
        final Map<Integer, String> tempNodeMapping = new TreeMap<>(nodeIndexToUrlMapping);
        tempNodeMapping.remove(node);
        return getResponsesFromReplicas(tempNodeMapping, fromOtherReplicas, request);
    }

    @NotNull
    private Response putIntoLocalNode(final Request request, final String keyString) {
        Response responseHttp;
        try {
            dao.upsert(util.getKey(keyString), util.getByteBufferValue(request));
            responseHttp = util.responseWithNoBody(Response.CREATED);
        } catch (IOException e) {
            responseHttp = util.responseWithNoBody(Response.INTERNAL_ERROR);
        }
        return responseHttp;
    }

    /**
     * Delete a record.
     *
     * @param idParam - key of the record to delete.
     */
    @Path("/v0/entity")
    @RequestMethod(Request.METHOD_DELETE)
    public void delete(final @Param("id") String idParam, final Request request, final HttpSession session) {
        try {
            executorService.execute(() -> internalRun(idParam, session,
                    () -> deleteResponseFromLocalAndReplicas(idParam, request)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    /**
     * Delete replication.
     *
     * @param idParam - key.
     * @param session - session.
     */
    @Path("/v0/entity/rep")
    @RequestMethod(Request.METHOD_DELETE)
    public void deleteReplication(final @Param("id") String idParam, final HttpSession session) {
        try {
            executorService.execute(() -> internalRun(idParam, session, () -> deleteInLocalNode(idParam)));
        } catch (RejectedExecutionException e) {
            util.send503Error(session);
        }
    }

    private void internalRun(final String idParam, final HttpSession session,
                             final Supplier<Response> responseSupplier) {
        Response responseHttp;
        if (idParam == null || idParam.isEmpty()) {
            responseHttp = util.responseWithNoBody(Response.BAD_REQUEST);
        } else {
            responseHttp = responseSupplier.get();
        }
        try {
            session.sendResponse(responseHttp);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
    }

    private Response deleteResponseFromLocalAndReplicas(final String idParam, final Request request) {
        final int node = util.getNode(idParam, nodeAmount);
        Response responseHttpTemp;
        if (node == thisNodeIndex) {
            responseHttpTemp = deleteInLocalNode(idParam);
        } else {
            responseHttpTemp = routeRequestToRemoteNode(request, node, nodeIndexToUrlMapping);
        }
        Response responseHttp;
        if (request.getParameter(SHOULD_REPLICATE, TRUE).equals(TRUE)) {
            responseHttp = replicatePutOrDelete(request, node, responseHttpTemp, 202);
        } else {
            responseHttp = responseHttpTemp;
        }
        return responseHttp;
    }

    @NotNull
    private Response deleteInLocalNode(final String idParam) {
        Response responseHttp;
        final ByteBuffer key = util.getKey(idParam);
        try {
            dao.remove(key);
            responseHttp = util.responseWithNoBody(Response.ACCEPTED);
        } catch (IOException e) {
            responseHttp = util.responseWithNoBody(Response.INTERNAL_ERROR);
        }
        return responseHttp;
    }

    private List<Response> getResponsesFromReplicas(final Map<Integer, String> tempNodeMapping, final int from,
                                                    final Request request) {
        final List<String> urls = new ArrayList<>(tempNodeMapping.values()).subList(0, from);
        final List<Response> responses = new ArrayList<>();
        for (final String url : urls) {
            try {
                responses.add(util.sendRequestToReplicas(httpClientMap.get(url), request));
            } catch (InterruptedException | PoolException | IOException | HttpException e) {
                responses.add(util.responseWithNoBody(Response.INTERNAL_ERROR));
            }
        }
        return responses;
    }

    private Response routeRequestToRemoteNode(final Request request, final int node,
                                              final Map<Integer, String> nodeMapping) {
        try {
            return util.sendRequestToRemote(httpClientMap.get(nodeMapping.get(node)), request);
        } catch (InterruptedException | PoolException | HttpException | IOException e) {
            return util.responseWithNoBody(Response.INTERNAL_ERROR);
        }
    }
}
