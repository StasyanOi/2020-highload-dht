package ru.mail.polis.service.stasyanoi;

import com.google.common.net.HttpHeaders;
import one.nio.http.*;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.DAO;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;

import static one.nio.http.Request.METHOD_DELETE;
import static one.nio.http.Request.METHOD_GET;
import static one.nio.http.Request.METHOD_PUT;

public class CustomServer extends HttpServer {

    private final DAO dao;

    public CustomServer(final DAO dao,
                        final HttpServerConfig config,
                        final Object... routers) throws IOException {
        super(config, routers);
        this.dao = dao;
    }

    /**
     * Get a record by key.
     *
     * @param idParam - key.
     * @return Response with the value.
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_GET)
    public Response get(final @Param("id") String idParam) {

        Response responseHttp;
        //check id param
        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {

            //get id as aligned byte buffer
            final ByteBuffer id = fromBytes(idParam.getBytes(StandardCharsets.UTF_8));
            //get the response from db
            try {
                final ByteBuffer body = dao.get(id);
                final byte[] bytes = toBytes(body);
                responseHttp = Response.ok(bytes);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (NoSuchElementException e) {
                //if not found then 404
                responseHttp = getResponseWithNoBody(Response.NOT_FOUND);
            }
        }

        return responseHttp;
    }

    @NotNull
    private Response getResponseWithNoBody(final String requestType) {
        final Response responseHttp = new Response(requestType);
        responseHttp.addHeader(HttpHeaders.CONTENT_LENGTH + ": " + 0);
        return responseHttp;
    }

    /**
     * ByteBuffer to byte array.
     *
     * @param buffer - input buffer.
     * @return byte array.
     */
    @NotNull
    public static byte[] toBytes(final ByteBuffer buffer) {
        final byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        buffer.clear();
        return bytes;
    }

    public static ByteBuffer fromBytes(final byte[] bytes) {
        return ByteBuffer.wrap(bytes);
    }

    /**
     * Create or update a record.
     *
     * @param idParam - key of the record.
     * @param request - request with the body.
     * @return Response with a status.
     * @throws IOException - read/write file exception.
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_PUT)
    public Response put(final @Param("id") String idParam,
                        final Request request) throws IOException {

        Response responseHttp;
        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            final ByteBuffer key = fromBytes(idParam.getBytes(StandardCharsets.UTF_8));
            final ByteBuffer value = fromBytes(request.getBody());
            dao.upsert(key, value);
            responseHttp = getResponseWithNoBody(Response.CREATED);
        }

        return responseHttp;
    }

    /**
     * Delete a record.
     *
     * @param idParam - key of the record to delete.
     * @return Response with a status.
     * @throws IOException - file read/write exception
     */
    @Path("/v0/entity")
    @RequestMethod(METHOD_DELETE)
    public Response delete(final @Param("id") String idParam) throws IOException {

        Response responseHttp;

        if (idParam == null || idParam.isEmpty()) {
            responseHttp = getResponseWithNoBody(Response.BAD_REQUEST);
        } else {
            final ByteBuffer key = fromBytes(idParam.getBytes(StandardCharsets.UTF_8));
            dao.remove(key);
            responseHttp = getResponseWithNoBody(Response.ACCEPTED);
        }
        return responseHttp;
    }

    /**
     * Default handler for unmapped requests.
     *
     * @param request - unmapped request
     * @param session - session object
     * @throws IOException - if input|output exceptions occur within the method
     */
    @Override
    public void handleDefault(final Request request, final HttpSession session) throws IOException {
        final Response response = getResponseWithNoBody(Response.BAD_REQUEST);
        session.sendResponse(response);
    }

    /**
     * Status check.
     *
     * @return Response with status.
     */
    @Path("/v0/status")
    @RequestMethod(METHOD_GET)
    public Response status() {
        return getResponseWithNoBody(Response.OK);
    }

    @Override
    public synchronized void start() {
        super.start();
    }

    @Override
    public synchronized void stop() {
        super.stop();
        try {
            dao.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
