/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2019-2020 Payara Foundation and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://github.com/payara/Payara/blob/master/LICENSE.txt
 * See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * The Payara Foundation designates this particular file as subject to the "Classpath"
 * exception as provided by the Payara Foundation in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package fish.payara.monitoring.web;

import static java.lang.Integer.parseInt;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.RequestScoped;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.stream.JsonParser;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import fish.payara.monitoring.adapt.GroupData;
import fish.payara.monitoring.adapt.GroupDataRepository;
import fish.payara.monitoring.adapt.MonitoringConsole;
import fish.payara.monitoring.adapt.MonitoringConsoleFactory;
import fish.payara.monitoring.adapt.MonitoringConsolePageConfig;
import fish.payara.monitoring.alert.Alert;
import fish.payara.monitoring.alert.AlertService;
import fish.payara.monitoring.alert.AlertService.AlertStatistics;
import fish.payara.monitoring.alert.Circumstance;
import fish.payara.monitoring.alert.Condition;
import fish.payara.monitoring.alert.Condition.Operator;
import fish.payara.monitoring.data.SeriesRepository;
import fish.payara.monitoring.alert.Watch;
import fish.payara.monitoring.model.Metric;
import fish.payara.monitoring.model.Series;
import fish.payara.monitoring.model.SeriesAnnotation;
import fish.payara.monitoring.model.SeriesDataset;
import fish.payara.monitoring.web.ApiRequests.DataType;
import fish.payara.monitoring.web.ApiRequests.SeriesQuery;
import fish.payara.monitoring.web.ApiRequests.SeriesRequest;
import fish.payara.monitoring.web.ApiResponses.AlertData;
import fish.payara.monitoring.web.ApiResponses.AlertsResponse;
import fish.payara.monitoring.web.ApiResponses.AnnotationData;
import fish.payara.monitoring.web.ApiResponses.CircumstanceData;
import fish.payara.monitoring.web.ApiResponses.ConditionData;
import fish.payara.monitoring.web.ApiResponses.RequestTraceResponse;
import fish.payara.monitoring.web.ApiResponses.SeriesData;
import fish.payara.monitoring.web.ApiResponses.SeriesMatch;
import fish.payara.monitoring.web.ApiResponses.SeriesResponse;
import fish.payara.monitoring.web.ApiResponses.WatchData;
import fish.payara.monitoring.web.ApiResponses.WatchesResponse;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class MonitoringConsoleResource {

    private static final Logger LOGGER = Logger.getLogger(MonitoringConsoleResource.class.getName());

    private SeriesRepository dataRepository;
    private AlertService alertService;
    private GroupDataRepository groupDataRepository;
    private MonitoringConsolePageConfig pageConfig;

    @PostConstruct
    private void init() {
        Iterator<MonitoringConsoleFactory> iter = ServiceLoader
                .load(MonitoringConsoleFactory.class, Thread.currentThread().getContextClassLoader()).iterator();
        MonitoringConsoleFactory facory = iter.hasNext() ? iter.next() : null;
        if (facory != null) {
            MonitoringConsole console = facory.getCreatedConsole();
            dataRepository = console.getService(SeriesRepository.class);
            alertService = console.getService(AlertService.class);
            groupDataRepository = console.getService(GroupDataRepository.class);
            pageConfig = console.getService(MonitoringConsolePageConfig.class);
        } else {
            LOGGER.log(Level.WARNING, "No MonitoringConsoleFactory defined using ServiceLoader mechanism.");
        }
    }

    private static Series seriesOrNull(String series) {
        try {
            return new Series(series);
        } catch (RuntimeException e) {
            LOGGER.log(Level.FINE, "Failed to parse series", e);
            return null;
        }
    }

    /**
     * @return JSON array with the IDs of the pages which have a server configuration
     */
    @GET
    @Path("/pages/")
    public String[] getPageNames() {
        return stream(pageConfig.listPages().spliterator(), false).sorted().toArray(String[]::new);
    }

    /**
     * @return A JSON object where each page is present as a field
     */
    @GET
    @Path("/pages/data/")
    public JsonObject getPageData() {
        JsonObjectBuilder obj = Json.createObjectBuilder();
        for (String name : pageConfig.listPages()) {
            String page = pageConfig.getPage(name);
            if (page != null && !page.isEmpty()) {
                try (JsonParser parser = Json.createParser(new StringReader(page))) {
                    if (parser.hasNext()) {
                        parser.next();
                        obj.add(name, parser.getObject());
                    }
                }
            }
        }
        return obj.build();
    }

    /**
     * @param name A page ID
     * @return JSON object for the page with the provided name
     */
    @GET
    @Path("/pages/data/{name}/")
    public String getPageData(@PathParam("name") String name) {
        return pageConfig.getPage(name);
    }

    /**
     * Updates the configuration of the provided page.
     * If JSON given for the page is null, empty, or an empty JSON object, the page is deleted.
     *
     * @param name A page ID
     * @param json JSON object with the page configuration
     * @return 204 NO_CONTENT if successful or 400 BAD_REQUEST when name was not provided
     */
    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/pages/data/{name}/")
    public Response updatePage(@PathParam("name") String name, String json) {
        if (name == null || name.isEmpty()) {
            return badRequest("Name missing");
        }
        if (json == null || json.isEmpty() || "{}".equals(json)) {
            return deletePage(name);
        }
        pageConfig.putPage(name, json);
        return noContent();
    }

    /**
     * Deletes the page configuration for the provided page ID.
     *
     * @param name A page ID
     * @return 204 NO_CONTENT when successful, also when no such page did exist
     */
    @DELETE
    @Path("/pages/data/{name}/")
    public Response deletePage(@PathParam("name") String name) {
        if (pageConfig.existsPage(name)) {
            pageConfig.removePage(name);
        }
        return noContent();
    }

    @GET
    @Path("/annotations/data/{series}/")
    public List<AnnotationData> getAnnotationsData(@PathParam("series") String series) {
        Series key = seriesOrNull(series);
        return key == null
                ? emptyList()
                : dataRepository.selectAnnotations(key).stream().map(AnnotationData::new).collect(toList());
    }

    @GET
    @Path("/series/data/{series}/")
    public SeriesResponse getSeriesData(@PathParam("series") String series) {
        return getSeriesData(new SeriesRequest(series));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/series/data/")
    public SeriesResponse getSeriesData(SeriesRequest request) {
        List<SeriesMatch> matches = new ArrayList<>(request.queries.length);
        for (SeriesQuery query : request.queries) {
            Series key = seriesOrNull(query.series);
            List<SeriesDataset> queryData = key == null || query.excludes(DataType.POINTS)
                    ? emptyList()
                    : dataRepository.selectSeries(key, query.instances);
            List<SeriesAnnotation> queryAnnotations = key == null || query.excludes(DataType.ANNOTATIONS)
                    ? emptyList()
                    : dataRepository.selectAnnotations(key, query.instances);
            Collection<Watch> queryWatches = key == null || query.excludes(DataType.WATCHES)
                    ? emptyList()
                    : alertService.wachtesFor(key);
            Collection<Alert> queryAlerts = key == null || query.excludes(DataType.ALERTS)
                    ? emptyList()
                    : alertService.alertsFor(key);
            matches.add(new SeriesMatch(query, query.series, queryData, queryAnnotations, queryWatches, queryAlerts));
        }
        if (request.groupBySeries) {
            return getGroupedSeriesData(matches);
        }
        return new SeriesResponse(matches, alertService.getAlertStatistics());
    }

    private SeriesResponse getGroupedSeriesData(List<SeriesMatch> matches) {
        Map<String, List<SeriesData>> dataBySeries = new HashMap<>();
        Map<String, List<AnnotationData>> annotationsBySeries = new HashMap<>();
        Map<String, List<WatchData>> watchesBySeries = new HashMap<>();
        Map<String, List<AlertData>> alertsBySeries = new HashMap<>();
        for (SeriesMatch match : matches) {
            for (SeriesData data : match.data) {
                dataBySeries.computeIfAbsent(data.series, key -> new ArrayList<>()).add(data);
            }
            for (AnnotationData annotation : match.annotations) {
                annotationsBySeries.computeIfAbsent(annotation.series, key -> new ArrayList<>()).add(annotation);
            }
            for (WatchData watch : match.watches) {
                watchesBySeries.computeIfAbsent(watch.series, key -> new ArrayList<>()).add(watch);
            }
            for (AlertData alert : match.alerts) {
                alertsBySeries.computeIfAbsent(alert.series, key -> new ArrayList<>()).add(alert);
            }
        }
        List<SeriesMatch> matchesBySeries = new ArrayList<>();
        for (Entry<String, List<SeriesData>> e : dataBySeries.entrySet()) {
            String series = e.getKey();
            matchesBySeries.add(new SeriesMatch(series, e.getValue(),
                    annotationsBySeries.getOrDefault(series, emptyList()),
                    watchesBySeries.getOrDefault(series, emptyList()),
                    alertsBySeries.getOrDefault(series, emptyList())));
        }
        return new SeriesResponse(matchesBySeries, alertService.getAlertStatistics());
    }

    @GET
    @Path("/series/")
    public String[] getSeriesNames() {
        return stream(dataRepository.selectAllSeries().spliterator(), false)
                .map(dataset -> dataset.getSeries().toString()).sorted().toArray(String[]::new);
    }

    @GET
    @Path("/instances/")
    public String[] getInstanceNames() {
        return dataRepository.instances().toArray(new String[0]);
    }

    @GET
    @Path("/trace/data/{series}/")
    public List<RequestTraceResponse> getTraceData(@PathParam("series") String series) {
        String group = series.split("" + Series.TAG_SEPARATOR)[1].substring(2);
        List<RequestTraceResponse> response = new ArrayList<>();
        for (GroupData trace : groupDataRepository.selectAll("requesttracing", group)) {
            response.add(new RequestTraceResponse(trace));
        }
        return response;
    }

    @GET
    @Path("/alerts/data/")
    public AlertsResponse getAlertsData() {
        return new AlertsResponse(alertService.alerts());
    }

    @GET
    @Path("/alerts/data/{series}/")
    public AlertsResponse getAlertsData(@PathParam("series") String seriesOrSerial) {
        if (seriesOrSerial.matches("\\d+")) {
            Alert alert = alertService.alertBySerial(parseInt(seriesOrSerial));
            return new AlertsResponse(alert == null ? emptyList() : singletonList(alert));
        }
        return new AlertsResponse(alertService.alertsFor(seriesOrNull(seriesOrSerial)));
    }

    @POST
    @Path("/alerts/ack/{serial}")
    public void acknowledgeAlert(@PathParam("serial") int serial) {
        Alert alert = alertService.alertBySerial(serial);
        if (alert != null) {
            alert.acknowledge();
        }
    }

    @GET
    @Path("/watches/data/")
    public WatchesResponse getWatchesData() {
        return new WatchesResponse(alertService.watches());
    }

    @DELETE
    @Path("/watches/data/{name}/")
    public Response deleteWatch(@PathParam("name") String name) {
        Watch watch = alertService.watchByName(name);
        if (watch != null) {
            alertService.removeWatch(watch);
        }
        return noContent();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    @Path("/watches/data/")
    public Response createWatch(WatchData data) {
        if (data.name == null || data.name.isEmpty()) {
            return badRequest("Name missing");
        }
        Circumstance red = createCircumstance(data.red);
        Circumstance amber = createCircumstance(data.amber);
        Circumstance green = createCircumstance(data.green);
        if (red.start.isNone() && amber.start.isNone()) {
            return badRequest("A start condition for red or amber must be given");
        }
        Metric metric = Metric.parse(data.series, data.unit);
        Watch watch = new Watch(data.name, metric, false, red, amber, green);
        alertService.addWatch(watch);
        return noContent();
    }

    @PATCH
    @Path("/watches/data/{name}/")
    public Response patchWatch(@PathParam("name") String name, @QueryParam("disable") boolean disable) {
        return alertService.toggleWatch(name, disable) ? noContent() : notFound();
    }

    private static Circumstance createCircumstance(CircumstanceData data) {
        if (data == null) {
            return Circumstance.UNSPECIFIED;
        }
        Circumstance res = new Circumstance(fish.payara.monitoring.alert.Alert.Level.parse(data.level),
                createCondition(data.start), createCondition(data.stop));
        if (data.suppress != null) {
            res = res.suppressedWhen(Metric.parse(data.surpressingSeries, data.surpressingUnit),
                    createCondition(data.suppress));
        }
        return res;
    }

    private static Condition createCondition(ConditionData data) {
        if (data == null) {
            return Condition.NONE;
        }
        Condition res = new Condition(Operator.parse(data.operator), data.threshold);
        if (data.forMillis != null) {
            res = res.forLastMillis(data.forMillis.longValue());
        }
        if (data.forTimes != null) {
            res = res.forLastTimes(data.forTimes.intValue());
        }
        if (data.onAverage) {
            res = res.onAverage();
        }
        return res;
    }

    private static Response badRequest(String reason) {
        return Response.status(Status.BAD_REQUEST.getStatusCode(),reason).build();
    }

    private static Response noContent() {
        return Response.noContent().build();
    }

    private static Response notFound() {
        return Response.status(Status.NOT_FOUND).build();
    }
}
