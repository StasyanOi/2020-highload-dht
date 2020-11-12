package ru.mail.polis.service.stasyanoi.server.internal;

import one.nio.http.HttpServer;
import one.nio.http.HttpServerConfig;
import one.nio.net.ConnectionString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.stasyanoi.DAOImpl;
import ru.mail.polis.service.stasyanoi.CustomExecutor;
import ru.mail.polis.service.stasyanoi.ResponseMerger;
import ru.mail.polis.service.stasyanoi.Util;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ConstantsServer extends HttpServer {

    protected final Map<Integer, String> nodeIndexToUrlMapping;
    protected final int nodeAmount;
    protected int thisNodeIndex;
    protected final DAO dao;
    protected CustomExecutor executorService = CustomExecutor.getExecutor();
    protected HttpClient asyncHttpClient;
    protected Logger logger = LoggerFactory.getLogger(ConstantsServer.class);

    /**
     * Fields server.
     *
     * @param dao - dao.
     * @param config - config.
     * @param topology - topology.
     * @throws IOException - IOException.
     */
    public ConstantsServer(final DAO dao, final HttpServerConfig config, final Set<String> topology)
            throws IOException {
        super(config);
        this.nodeAmount = topology.size();
        final ArrayList<String> urls = new ArrayList<>(topology);
        urls.sort(String::compareTo);

        this.nodeIndexToUrlMapping = new TreeMap<>();
        this.asyncHttpClient = HttpClient.newHttpClient();
        for (int i = 0; i < urls.size(); i++) {
            nodeIndexToUrlMapping.put(i, urls.get(i));
            if (urls.get(i).contains(String.valueOf(super.port))) {
                thisNodeIndex = i;
            }
        }
        this.dao = dao;
    }
}
