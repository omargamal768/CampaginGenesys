package com.example.Campagin.service;

import com.example.Campagin.config.RetryUtils;
import com.example.Campagin.model.Attempt;
import com.example.Campagin.model.Callback;
import com.example.Campagin.model.ScimUserResponse;
import com.example.Campagin.repo.AttemptRepository;
import com.example.Campagin.repo.CallbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mypurecloud.sdk.v2.ApiClient;
import com.mypurecloud.sdk.v2.Configuration;
import com.mypurecloud.sdk.v2.api.OutboundApi;
import com.mypurecloud.sdk.v2.model.DomainEntityRef;
import com.mypurecloud.sdk.v2.model.ExportUri;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class GenesysService {

    private static final ZoneId DUBAI_TZ = ZoneId.of("Asia/Dubai");
    @Value("${genesys.client-id}")
    private String clientId;

    @Value("${genesys.client-secret}")
    private String clientSecret;

    @Value("${genesys.region}")
    private String region;

    @Value("${campaignId}")
    private String campaignId;
    private final RestTemplate restTemplate;
    private final AttemptRepository attemptRepository;
    private final CallbackRepository callbackRepository;
    private static final Logger logger = LoggerFactory.getLogger(GenesysService.class);

    public GenesysService(RestTemplate restTemplate, AttemptRepository attemptRepository, CallbackRepository callbackRepository) {
        this.restTemplate = restTemplate;
        this.attemptRepository = attemptRepository;
        this.callbackRepository = callbackRepository;
    }

    public String getAccessToken() {
        String authUrl = String.format("https://login.%s/oauth/token", region);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        String authString = clientId + ":" + clientSecret;
        String base64AuthString = java.util.Base64.getEncoder().encodeToString(authString.getBytes());
        headers.set("Authorization", "Basic " + base64AuthString);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "client_credentials");

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(body, headers);

        try {
            String tokenResponse = restTemplate.postForObject(authUrl, requestEntity, String.class);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(tokenResponse);
            String token = root.path("access_token").asText();

            if (token == null || token.isEmpty()) {
                throw new RuntimeException("Access token not found in response");
            }

            logger.info("✅ Token retrieved successfully");
            return token;

        } catch (HttpClientErrorException e) {
            logger.error("🔴 [getAccessToken] HTTP error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new RuntimeException("Failed to get access token: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            logger.error("🔴 [getAccessToken] Unexpected error: {}", e.getMessage(), e);
            throw new RuntimeException("Unexpected error while getting access token", e);
        }
    }

    public void getConversationsAndStore() {
        String url = String.format("https://api.%s/api/v2/analytics/conversations/details/query", region);
        String token = getAccessToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);

        int pageNumber = 1;
        int pageSize = 100;
        int totalHits = 0;

        // Loop to handle pagination
        while (true) {
            String body = String.format("""
                    {
                       "interval": "2025-08-24T00:00:00Z/2025-08-24T23:59:59.000Z",
                       "order": "asc",
                       "paging": {
                         "pageSize": %d,
                         "pageNumber": %d
                       }
                     }
                
                """, pageSize, pageNumber);

            HttpEntity<String> requestEntity = new HttpEntity<>(body, headers);

            try {
                String response = restTemplate.postForObject(url, requestEntity, String.class);
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                if (pageNumber == 1) {
                    totalHits = root.get("totalHits").asInt();
                    logger.info("✅ Total conversations found: {}", totalHits);
                }

                JsonNode conversations = root.path("conversations");
                if (!conversations.isArray() || conversations.isEmpty()) {
                    logger.info("❌ No more conversations to retrieve. Exiting loop.");
                    break;
                }

                for (JsonNode conversationNode : conversations) {
                    processConversation(conversationNode);
                }

                logger.info("✅ Successfully processed page {} with {} conversations.", pageNumber, conversations.size());

                if (pageNumber * pageSize >= totalHits) {
                    logger.info("✅ All conversations have been retrieved.");
                    break;
                }

                pageNumber++;

            } catch (Exception e) {
                logger.error("❌ [getConversationsAndStore] Error on page {}: {}", pageNumber, e.getMessage(), e);
                throw new RuntimeException("Unexpected error while fetching conversations", e);
            }
        }
    }

    private void processConversation(JsonNode conv) {
        boolean campaignMatch = false;

        // ✅ Check if any participant.session has the configured campaignId
        for (JsonNode participant : conv.path("participants")) {
            for (JsonNode session : participant.path("sessions")) {
                String convCampaignId = session.path("outboundCampaignId").asText();
                if (campaignId.equals(convCampaignId)) {
                    campaignMatch = true;
                    logger.info("******************"+convCampaignId);
                    break;
                }
            }
            if (campaignMatch) break;
        }

        // ❌ Skip if this conversation doesn't belong to our campaign
        if (!campaignMatch) {
            for (JsonNode participant : conv.path("participants")) {
                for (JsonNode session : participant.path("sessions")) {
                    String convCampaignId = session.path("outboundCampaignId").asText();
                    logger.info("Conversation {} skipped because outboundCampaignId IS {} != configured campaignId {}",
                            conv.path("conversationId").asText(), convCampaignId, campaignId);
                }}
            return;
        }

        List<Attempt> attempts = new ArrayList<>();
        List<Callback> callbacks = new ArrayList<>();

        for (JsonNode participant : conv.path("participants")) {
            String purpose = participant.path("purpose").asText();
            if ("customer".equalsIgnoreCase(purpose)) {
                for (JsonNode session : participant.path("sessions")) {
                    if ("voice".equalsIgnoreCase(session.path("mediaType").asText())) {
                        Attempt attempt = mapJsonToAttempt(conv, session, participant);
                        if (attempt != null) {
                            attempts.add(attempt);
                        }
                    }
                }
            } else if ("acd".equalsIgnoreCase(purpose)) {
                for (JsonNode session : participant.path("sessions")) {
                    if ("agent".equalsIgnoreCase(session.path("flowInType").asText())) {
                        Callback callback = mapJsonToCallback(conv, session);
                        if (callback != null) {
                            callbacks.add(callback);
                        }
                    }
                }
            }
        }

        // ✅ Insert attempts if new
        if (!attempts.isEmpty()) {
            Set<String> newAttemptIds = attempts.stream()
                    .map(Attempt::getCustomerSessionId)
                    .collect(Collectors.toSet());
            Set<String> existingAttemptIds = attemptRepository.findByCustomerSessionIdIn(newAttemptIds).stream()
                    .map(Attempt::getCustomerSessionId)
                    .collect(Collectors.toSet());

            List<Attempt> newAttempts = new ArrayList<>();
            for (Attempt attempt : attempts) {
                String id = attempt.getCustomerSessionId();
                if (existingAttemptIds.contains(id)) {
                    logger.info("Attempt with customerSessionId: {} already exists. Action: SKIPPED", id);
                } else {
                    newAttempts.add(attempt);
                    logger.info("Attempt with customerSessionId: {} does not exist. Action: INSERTED", id);
                }
            }

            if (!newAttempts.isEmpty()) {
                attemptRepository.saveAll(newAttempts);
                logger.info("✅ Saved {} new voice attempts to the database.", newAttempts.size());
            } else {
                logger.info("No new voice attempts to save.");
            }
        }

        // ✅ Insert callbacks if new
        if (!callbacks.isEmpty()) {
            Set<String> newCallbackKeys = callbacks.stream()
                    .map(cb -> cb.getConversationId() + ":" + cb.getOutboundContactId())
                    .collect(Collectors.toSet());

            Set<String> existingCallbackKeys = callbackRepository.findByConversationIdInAndOutboundContactIdIn(
                            callbacks.stream().map(Callback::getConversationId).collect(Collectors.toList()),
                            callbacks.stream().map(Callback::getOutboundContactId).collect(Collectors.toList())
                    ).stream()
                    .map(cb -> cb.getConversationId() + ":" + cb.getOutboundContactId())
                    .collect(Collectors.toSet());

            List<Callback> newCallbacks = new ArrayList<>();
            for (Callback callback : callbacks) {
                String key = callback.getConversationId() + ":" + callback.getOutboundContactId();
                if (existingCallbackKeys.contains(key)) {
                    logger.info("Callback with key: {} already exists. Action: SKIPPED", key);
                } else {
                    newCallbacks.add(callback);
                    logger.info("Callback with key: {} does not exist. Action: INSERTED", key);
                }
            }

            if (!newCallbacks.isEmpty()) {
                callbackRepository.saveAll(newCallbacks);
                logger.info("✅ Saved {} new ACD agent callbacks to the database.", newCallbacks.size());
            } else {
                logger.info("No new ACD agent callbacks to save.");
            }
        }
    }





    private Attempt mapJsonToAttempt(JsonNode conv, JsonNode session, JsonNode participant) {
        if (!"customer".equalsIgnoreCase(participant.path("purpose").asText()) || !"voice".equalsIgnoreCase(session.path("mediaType").asText())) {
            return null;
        }

        if (hasOpenSegment(session, Set.of("interact", "dialing", "alert"))) {
            return null;
        }

        Attempt attempt = new Attempt();
        String customerSessionId = session.path("sessionId").asText("");
        attempt.setCustomerSessionId(customerSessionId);
        attempt.setConversationId(conv.path("conversationId").asText());
        String outboundContactId = session.path("outboundContactId").asText("");
        String campaignId = session.path("outboundCampaignId").asText("");
attempt.setStatus(false);

//      String accessToken = getAccessToken();
//      String downloadUri = initiateContactExport(accessToken);
//      List<List<String>> rows = syncContactsFromGenesysApi();
//      String csvContentt = readExportData(downloadUri, accessToken);
//      String OrderId = fetchOrderId(outboundContactId,rows);


        attempt.setCampaignId(campaignId);
        attempt.setOutboundContactId(outboundContactId);
        attempt.setOrderId(outboundContactId);
        String phoneNumber = session.path("dnis").asText();
        attempt.setPhone(phoneNumber.replace("tel:6990072", ""));
        Pair<String, String> firstLastTimes = getFirstLastTimes(session);
        attempt.setStartGst(parseIso8601ToGst(firstLastTimes.getLeft()));
        attempt.setEndGst(parseIso8601ToGst(firstLastTimes.getRight()));
        long dialSeconds = Math.round(sumSegmentDurations(session, "dialing"));
        attempt.setDialSeconds((int) dialSeconds);
        JsonNode metricsMap = collectMetricsMap(session);
        Double talkSeconds = msToSeconds(metricsMap.path("tConnected").asDouble(0.0));
        if (talkSeconds == null || talkSeconds == 0.0) {
            talkSeconds = roundToOneDecimalPlace(totalInteractDuration(session));
        }
        attempt.setTalkSeconds(talkSeconds);
        String outcome = hasInteract(session) ? "Connected" : "Not connected";
        attempt.setOutcome(outcome);
        attempt.setDisconnect(getDisconnectType(session));
        attempt.setSip(getSipCodes(session));
        Pair<JsonNode, JsonNode> peerResult = findPeerSession(conv, customerSessionId);
        JsonNode peerSession = peerResult.getLeft();
        JsonNode peerParticipant = peerResult.getRight();
        if (peerSession != null) {
            attempt.setPeerSessionId(peerSession.path("sessionId").asText(""));
            attempt.setPeerPurpose(peerParticipant != null ? peerParticipant.path("purpose").asText("") : "");
            attempt.setPeerDisposition(peerSession.path("dispositionName").asText(""));
            attempt.setPeerAnalyzer(peerSession.path("dispositionAnalyzer").asText(""));
            attempt.setPeerWrapUpCode(getWrapUpCode(peerSession));
            attempt.setPeerSip(getSipCodes(peerSession));
            attempt.setPeerProtocolCallId(peerSession.path("protocolCallId").asText(""));
            attempt.setPeerSessionDnis(peerSession.path("sessionDnis").asText(""));
            attempt.setPeerProvider(peerSession.path("provider").asText(""));
        }
        String selectedAgentId = session.path("selectedAgentId").asText("");
        Pair<LocalDateTime, LocalDateTime> custInteractWindow = getInteractWindow(session);
        Pair<JsonNode, JsonNode> agentResult = findAgentSession(conv, selectedAgentId, customerSessionId, custInteractWindow);
        JsonNode agentSession = agentResult.getLeft();
        JsonNode agentParticipant = agentResult.getRight();
        if (agentSession != null) {
            JsonNode agentMetricsMap = collectMetricsMap(agentSession);
            attempt.setAgentSessionId(agentSession.path("sessionId").asText(""));
            attempt.setAgentUserId(agentParticipant != null ? agentParticipant.path("userId").asText("") : "");
            attempt.setAgentAlertSeconds(msToSeconds(agentMetricsMap.path("tAlert").asDouble(0.0)));
            attempt.setAgentAnsweredSeconds(msToSeconds(agentMetricsMap.path("tAnswered").asDouble(0.0)));
            Double agentTalkSecs = msToSeconds(agentMetricsMap.path("tTalk").asDouble(0.0));

            Double agentHoldSecs = msToSeconds(agentMetricsMap.path("tHeld").asDouble(0.0));
            if (agentHoldSecs == null) {
                agentHoldSecs = 0.0;
            }
            if (agentTalkSecs == null || agentTalkSecs == 0.0) {
                agentTalkSecs = roundToOneDecimalPlace(totalInteractDuration(agentSession));
            }

            attempt.setAgentTalkSeconds(agentTalkSecs);
            attempt.setAgentAcwSeconds(msToSeconds(agentMetricsMap.path("tAcw").asDouble(0.0)));
            double ACW = msToSeconds(agentMetricsMap.path("tAcw").asDouble(0.0));
            attempt.setAgentHandleSeconds(msToSeconds(agentMetricsMap.path("tHandle").asDouble(0.0)));
            attempt.setAgentWrapUp(getWrapUpCode(agentSession));
            attempt.setAgentHoldSeconds(agentHoldSecs);
            attempt.setDuration(agentTalkSecs + agentHoldSecs + ACW);
            attempt.setAgentWrapUpName(fetchWrapUpName(getWrapUpCode(agentSession)));

        }
        attempt.setAgentEmail(fetchAgentEmail(attempt.getAgentUserId()));

        return attempt;
    }

    private Callback mapJsonToCallback(JsonNode conv, JsonNode session) {
        if (!"agent".equalsIgnoreCase(session.path("flowInType").asText())) {
            return null;
        }
        Callback callback = new Callback();
        callback.setConversationId(conv.path("conversationId").asText(""));
        callback.setOutboundContactId(session.path("outboundContactId").asText(""));
        callback.setCallbackScheduledGst(parseIso8601ToGst(session.path("callbackScheduledTime").asText()));
        List<String> numbers = new ArrayList<>();
        JsonNode numbersNode = session.path("callbackNumbers");
        if (numbersNode.isArray()) {
            for (JsonNode num : numbersNode) {
                numbers.add(num.asText(""));
            }
        }
        callback.setCallbackNumbers(String.join(", ", numbers));
        return callback;
    }

    // --- Helper Methods ---

    private LocalDateTime parseIso8601(String dtStr) {
        if (dtStr == null || dtStr.isEmpty()) {
            return null;
        }
        try {
            return ZonedDateTime.parse(dtStr).toLocalDateTime();
        } catch (DateTimeParseException e) {
            logger.warn("Failed to parse ISO 8601 date string: {}", dtStr, e);
            return null;
        }
    }

    private LocalDateTime parseIso8601ToGst(String isoString) {
        if (isoString == null || isoString.isEmpty()) {
            return null;
        }
        try {
            ZonedDateTime utcTime = ZonedDateTime.parse(isoString);
            return utcTime.withZoneSameInstant(DUBAI_TZ).toLocalDateTime();
        } catch (DateTimeParseException e) {
            try {
                LocalDateTime localDateTime = LocalDateTime.parse(isoString);
                return localDateTime.atZone(ZoneId.of("UTC")).withZoneSameInstant(DUBAI_TZ).toLocalDateTime();
            } catch (DateTimeParseException innerE) {
                logger.error("Failed to parse date: {}", isoString, innerE);
                return null;
            }
        }
    }

    private double durationSeconds(String start, String end) {
        LocalDateTime s = parseIso8601(start);
        LocalDateTime e = parseIso8601(end);
        if (s == null || e == null) {
            return 0.0;
        }
        return Math.max(0.0, (double) java.time.Duration.between(s, e).toMillis() / 1000.0);
    }

    private JsonNode collectMetricsMap(JsonNode session) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode metricsNode = session.path("metrics");
        if (metricsNode.isArray()) {
            ObjectNode metricsMap = mapper.createObjectNode();
            for (JsonNode metric : metricsNode) {
                String name = metric.path("name").asText("");
                if (!name.isEmpty()) {
                    metricsMap.set(name, metric.path("value"));
                }
            }
            return metricsMap;
        }
        return mapper.createObjectNode();
    }

    private double sumSegmentDurations(JsonNode session, String segType) {
        double total = 0.0;
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                if (segType.equalsIgnoreCase(seg.path("segmentType").asText())) {
                    total += durationSeconds(seg.path("segmentStart").asText(), seg.path("segmentEnd").asText());
                }
            }
        }
        return total;
    }

    private Pair<String, String> getFirstLastTimes(JsonNode session) {
        List<LocalDateTime> starts = new ArrayList<>();
        List<LocalDateTime> ends = new ArrayList<>();
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                LocalDateTime segStart = parseIso8601(seg.path("segmentStart").asText());
                LocalDateTime segEnd = parseIso8601(seg.path("segmentEnd").asText());
                if (segStart != null) starts.add(segStart);
                if (segEnd != null) ends.add(segEnd);
            }
        }

        Optional<LocalDateTime> minStart = starts.stream().min(LocalDateTime::compareTo);
        Optional<LocalDateTime> maxEnd = ends.stream().max(LocalDateTime::compareTo);

        return new Pair<>(minStart.map(LocalDateTime::toString).orElse(null), maxEnd.map(LocalDateTime::toString).orElse(null));
    }

    private String getDisconnectType(JsonNode session) {
        LocalDateTime lastEnd = null;
        String lastDisc = "";
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                LocalDateTime se = parseIso8601(seg.path("segmentEnd").asText());
                if (se != null && (lastEnd == null || se.isAfter(lastEnd))) {
                    lastEnd = se;
                    lastDisc = seg.path("disconnectType").asText("");
                }
            }
        }
        return lastDisc;
    }

    private String getSipCodes(JsonNode session) {
        Set<String> sips = new HashSet<>();
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                JsonNode sipCodesNode = seg.path("sipResponseCodes");
                if (sipCodesNode.isArray()) {
                    for (JsonNode code : sipCodesNode) {
                        sips.add(String.valueOf(code.asInt()));
                    }
                }
            }
        }
        return sips.stream().sorted().collect(Collectors.joining(","));
    }

    private boolean hasInteract(JsonNode session) {
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                if ("interact".equalsIgnoreCase(seg.path("segmentType").asText())) {
                    if (durationSeconds(seg.path("segmentStart").asText(), seg.path("segmentEnd").asText()) > 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private double totalInteractDuration(JsonNode session) {
        return sumSegmentDurations(session, "interact");
    }

    private Pair<JsonNode, JsonNode> findPeerSession(JsonNode conv, String customerSessionId) {
        List<Pair<JsonNode, JsonNode>> matches = new ArrayList<>();
        JsonNode participantsNode = conv.path("participants");
        if (participantsNode.isArray()) {
            for (JsonNode p : participantsNode) {
                JsonNode sessionsNode = p.path("sessions");
                if (sessionsNode.isArray()) {
                    for (JsonNode s : sessionsNode) {
                        if (customerSessionId.equals(s.path("peerId").asText())) {
                            matches.add(new Pair<>(s, p));
                        }
                    }
                }
            }
        }

        if (matches.isEmpty()) {
            return new Pair<>(null, null);
        }

        matches.sort(Comparator.comparingInt(sp -> {
            String purpose = sp.getRight().path("purpose").asText("").toLowerCase();
            switch (purpose) {
                case "outbound":
                    return 0;
                case "acd":
                    return 1;
                case "agent":
                    return 2;
                default:
                    return 99;
            }
        }));
        return matches.get(0);
    }

    private String getWrapUpCode(JsonNode session) {
        if (session == null) {
            return "";
        }
        LocalDateTime lastEnd = null;
        String lastCode = "";
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                if ("wrapup".equalsIgnoreCase(seg.path("segmentType").asText())) {
                    LocalDateTime se = parseIso8601(seg.path("segmentEnd").asText());
                    if (se != null && (lastEnd == null || se.isAfter(lastEnd))) {
                        lastEnd = se;
                        lastCode = seg.path("wrapUpCode").asText("");
                    }
                }
            }
        }
        return lastCode;
    }

    private Pair<LocalDateTime, LocalDateTime> getInteractWindow(JsonNode session) {
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                if ("interact".equalsIgnoreCase(seg.path("segmentType").asText())) {
                    LocalDateTime s = parseIso8601(seg.path("segmentStart").asText());
                    LocalDateTime e = parseIso8601(seg.path("segmentEnd").asText());
                    if (s != null && e != null && e.isAfter(s)) {
                        return new Pair<>(s, e);
                    }
                }
            }
        }
        return new Pair<>(null, null);
    }

    private double overlapSeconds(Pair<LocalDateTime, LocalDateTime> a, Pair<LocalDateTime, LocalDateTime> b) {
        if (a.getLeft() == null || a.getRight() == null || b.getLeft() == null || b.getRight() == null) {
            return 0.0;
        }
        LocalDateTime start = max(a.getLeft(), b.getLeft());
        LocalDateTime end = min(a.getRight(), b.getRight());
        return Math.max(0.0, (double) java.time.Duration.between(start, end).toMillis() / 1000.0);
    }

    private LocalDateTime max(LocalDateTime dt1, LocalDateTime dt2) {
        return dt1.isAfter(dt2) ? dt1 : dt2;
    }

    private LocalDateTime min(LocalDateTime dt1, LocalDateTime dt2) {
        return dt1.isBefore(dt2) ? dt1 : dt2;
    }

    private Pair<JsonNode, JsonNode> findAgentSession(
            JsonNode conv,
            String selectedAgentId,
            String custSessionId,
            Pair<LocalDateTime, LocalDateTime> custInteractWindow) {

        JsonNode bestSession = null;
        JsonNode bestParticipant = null;
        Pair<Integer, Double> bestScore = new Pair<>(-1, 0.0);

        JsonNode participantsNode = conv.path("participants");
        if (participantsNode.isArray()) {
            for (JsonNode p : participantsNode) {
                if (!"agent".equalsIgnoreCase(p.path("purpose").asText())) {
                    continue;
                }
                if (!selectedAgentId.isEmpty() && !selectedAgentId.equals(p.path("userId").asText())) {
                    continue;
                }

                JsonNode sessionsNode = p.path("sessions");
                if (sessionsNode.isArray()) {
                    for (JsonNode s : sessionsNode) {
                        if (!"voice".equalsIgnoreCase(s.path("mediaType").asText())) {
                            continue;
                        }

                        String agentPeer = s.path("peerId").asText("");
                        int peerMatch = agentPeer.equals(custSessionId) ? 1 : 0;
                        Pair<LocalDateTime, LocalDateTime> agentWin = getInteractWindow(s);
                        double overlap = 0.0;
                        if (custInteractWindow.getLeft() != null && custInteractWindow.getRight() != null && agentWin.getLeft() != null && agentWin.getRight() != null) {
                            overlap = overlapSeconds(custInteractWindow, agentWin);
                        }

                        Pair<Integer, Double> currentScore = new Pair<>(peerMatch, overlap);
                        if (currentScore.getLeft() > bestScore.getLeft() || (currentScore.getLeft().equals(bestScore.getLeft()) && currentScore.getRight() > bestScore.getRight())) {
                            bestScore = currentScore;
                            bestSession = s;
                            bestParticipant = p;
                        }
                    }
                }
            }
        }

        if (bestSession != null && (bestScore.getLeft() == 1 || bestScore.getRight() > 0.0)) {
            return new Pair<>(bestSession, bestParticipant);
        }
        return new Pair<>(null, null);
    }

    private Double msToSeconds(double val) {
        if (val == 0.0) return null;
        return Math.round(val / 1000.0 * 10.0) / 10.0;
    }

    private Double roundToOneDecimalPlace(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private boolean hasOpenSegment(JsonNode session, Set<String> segTypes) {
        JsonNode segmentsNode = session.path("segments");
        if (segmentsNode.isArray()) {
            for (JsonNode seg : segmentsNode) {
                if (segTypes.contains(seg.path("segmentType").asText()) && seg.has("segmentStart") && !seg.has("segmentEnd")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static class Pair<L, R> {
        private final L left;
        private final R right;

        public Pair(L left, R right) {
            this.left = left;
            this.right = right;
        }

        public L getLeft() {
            return left;
        }

        public R getRight() {
            return right;
        }
    }

    public String fetchAgentEmail(String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }

        String accessToken = getAccessToken();
        if (accessToken == null) {
            logger.error("Failed to obtain Access Token for SCIM Users API.");
            return null;
        }

        String scimUserUrl = String.format("https://api.%s/api/v2/scim/users/%s", region, userId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            logger.info("🔍 Fetching Agent Email for User ID: {}", userId);

            ResponseEntity<ScimUserResponse> response = restTemplate.exchange(
                    scimUserUrl,
                    HttpMethod.GET,
                    requestEntity,
                    ScimUserResponse.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                logger.info("✅ Successfully fetched Agent Email for User ID: {}", userId);
                return response.getBody().getEmails().get(0).getValue();
            } else {
                throw new RuntimeException("❌ Failed to fetch Agent Email. Status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            logger.error("🚫 Failed permanently to fetch Agent data for User ID: {}: {}", userId, e.getMessage(), e);
            return null;
        }
    }
//    public List<List<String>> syncContactsFromGenesysApi() {
//        logger.info("************Start First Iteration of Genesys Cloud **************");
//        logger.info("=== [SYNC START] Initiating synchronization with Genesys Cloud contacts ===");
//        String accessToken = getAccessToken();
//        logger.info("[SUCCESS]    Token", accessToken);
//        String downloadUri = initiateContactExport(accessToken);
//        logger.info("[SUCCESS]    downloadUri", downloadUri);
//        String csvContent = readExportData(downloadUri, accessToken);
//        logger.info("[SUCCESS]    csvContent", csvContent.toString());
//        List<List<String>> rows = new ArrayList<>();
//        try (CSVParser parser = new CSVParser(new StringReader(csvContent),
//                CSVFormat.DEFAULT.builder()
//                        .setHeader()              // use header from CSV
//                        .setSkipHeaderRecord(true) // skip header row in iteration
//                        .setTrim(true)
//                        .build())) {
//
//            for (CSVRecord record : parser) {
//                List<String> row = new ArrayList<>();
//                record.forEach(row::add);
//                rows.add(row);
//            }
//        } catch (Exception e) {
//            logger.error("❌ Failed to parse CSV: {}", e.getMessage(), e);
//        }
//
//        // ✅ Log all rows
//        logger.info("📋 Parsed {} rows from CSV", rows.size());
//        rows.forEach(r -> logger.info("Row: {}", r));
//
//        return rows ;
//    }
//    private String initiateContactExport(String token) {
//        String contactListId = "421ca5e3-8fe2-4975-88aa-3323898a6b23";
//        String region = "mec1";
//        try {
//            ApiClient apiClient = ApiClient.Builder.standard()
//                    .withAccessToken(token)
//                    .withBasePath("https://api." + region + ".pure.cloud")
//                    .build();
//            Configuration.setDefaultApiClient(apiClient);
//            OutboundApi outboundApi = new OutboundApi();
//            DomainEntityRef postResponse = RetryUtils.retry(3, 12000, () -> {
//                logger.info("📤 Triggering new export for contact list {}", contactListId);
//                return outboundApi.postOutboundContactlistExport(contactListId, null);
//            });
//            String exportJobId = postResponse.getId();
//            logger.info("✅ Export job initiated. Job ID: {}", exportJobId);
//            String downloadUri = RetryUtils.retry(60, 5000, () -> {
//                logger.info("⏳ Polling export status for Job ID {}...", exportJobId);
//                ExportUri exportUri = outboundApi.getOutboundContactlistExport(contactListId, exportJobId);
//                if (exportUri != null && exportUri.getUri() != null && !exportUri.getUri().isEmpty()) {
//                    logger.info("✅ Export ready: {}", exportUri.getUri());
//                    return exportUri.getUri();
//                } else {
//                    throw new RuntimeException("Export not ready yet.");
//                }
//            });
//            if (downloadUri == null) {
//                throw new RuntimeException("❌ Export did not complete in time");
//            }
//            return downloadUri;
//        } catch (Exception e) {
//            logger.error("🚫 Unexpected export error: {}", e.getMessage(), e);
//            throw new RuntimeException("Contact export ultimately failed", e);
//        }
//    }
//
//    private String readExportData(String downloadUri, String token) {
//        HttpHeaders headers = new HttpHeaders();
//        headers.setBearerAuth(token);
//        HttpEntity < Void > requestEntity = new HttpEntity < > (headers);
//
//        try {
//            logger.info("Attempting to fetch content from the download link: {}", downloadUri);
//
//            // Fetch initial response from the provided download URI
//            ResponseEntity < String > initialResponse = restTemplate.exchange(downloadUri, HttpMethod.GET, requestEntity, String.class);
//            String content = initialResponse.getBody();
//
//            if (content != null && content.trim().startsWith("<!DOCTYPE html>")) {
//                // Case: HTML content instead of CSV
//                logger.error("[STEP 1 - ERROR] HTML content received from URI: {}", downloadUri);
//                logger.info("[STEP 2] Attempting to extract the direct CSV link from HTML...");
//                String directCsvLink = extractDirectCsvLink(content);
//
//                if (directCsvLink != null) {
//                    logger.info("[STEP 3] Direct CSV link found: {}. Attempting to download from this link...", directCsvLink);
//                    ResponseEntity < String > csvResponse = restTemplate.exchange(directCsvLink, HttpMethod.GET, requestEntity, String.class);
//                    return csvResponse.getBody();
//                } else {
//                    logger.error("[STEP 2 - ERROR] Unable to extract CSV link from HTML content.");
//                    return content;
//                }
//            } else {
//                // Case: CSV content received directly
//                return content;
//            }
//        } catch (HttpClientErrorException e) {
//            // Handle HTTP client errors
//            logger.error("[ERROR] HTTP error while fetching data from {}: {} - {}", downloadUri, e.getStatusCode(), e.getResponseBodyAsString());
//            throw new RuntimeException("Failed to fetch data: " + e.getResponseBodyAsString(), e);
//        } catch (Exception e) {
//            // Handle unexpected errors
//            logger.error("[ERROR] Unexpected error while fetching data from {}: {}", downloadUri, e.getMessage());
//            throw new RuntimeException("Failed to fetch data.", e);
//        }
//    }
//    //Method used in step 3 to extract uri
//    private String extractDirectCsvLink(String htmlContent) {
//        Pattern pattern = Pattern.compile("href=\"(https?://[^\"]+\\.csv)\"|url='(https?://[^']+\\.csv)'", Pattern.CASE_INSENSITIVE);
//        Matcher matcher = pattern.matcher(htmlContent);
//        if (matcher.find()) {
//            if (matcher.group(1) != null) {
//                return matcher.group(1);
//            } else if (matcher.group(2) != null) {
//                return matcher.group(2);
//            }
//        }
//        Pattern metaRefreshPattern = Pattern.compile("<meta\\s+http-equiv=['\"]refresh['\"]\\s+content=['\"]\\d+;\\s*url=(https?://[^'\"]+\\.csv)['\"]", Pattern.CASE_INSENSITIVE);
//        Matcher metaRefreshMatcher = metaRefreshPattern.matcher(htmlContent);
//        if (metaRefreshMatcher.find()) {
//            return metaRefreshMatcher.group(1);
//        }
//        logger.warn("No direct CSV link found in the HTML content. " +
//                "Will attempt to download from the original URI, but it may still be HTML. " +
//                "Content sample: {}", htmlContent.substring(0, Math.min(htmlContent.length(), 500)));
//        return null;
//    }

//    public static String fetchOrderId(String contactOutbound, List<List<String>> rows) {
//        for (List<String> row : rows) {
//            if (!row.isEmpty() && row.get(0).equals(contactOutbound)) {
//                String orderId = row.size() > 1 ? row.get(1) : null;
//                if (orderId != null) {
//                    logger.info("Found Order ID: " + orderId);
//                }
//                return orderId;
//            }
//        }
//        logger.info("No Order ID found for contactOutbound: " + contactOutbound);
//        return null;
//    }


    public String fetchWrapUpName(String wrapUpId) {
        if (wrapUpId == null || wrapUpId.isEmpty()) {
            return null;
        }

        String accessToken = getAccessToken();
        if (accessToken == null) {
            logger.error("Failed to obtain Access Token for fetching wrap-up name.");
            return null;
        }

        String wrapUpUrl = "https://apps.mec1.pure.cloud/platform/api/v2/routing/wrapupcodes?pageSize=100";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    wrapUpUrl,
                    HttpMethod.GET,
                    requestEntity,
                    JsonNode.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode entities = response.getBody().path("entities");
                if (entities.isArray()) {
                    for (JsonNode wrapUp : entities) {
                        String id = wrapUp.path("id").asText();
                        if (wrapUpId.equals(id)) {
                            String name = wrapUp.path("name").asText(null);
                            logger.info("✅ Fetched WrapUp Name '{}' for WrapUp ID: {}", name, wrapUpId);
                            return name;
                        }
                    }
                }
                logger.warn("❌ WrapUp ID '{}' not found in response", wrapUpId);
                return null;
            } else {
                logger.warn("❌ Failed to fetch WrapUp Name for WrapUp ID: {}. Status: {}", wrapUpId, response.getStatusCode());
                return null;
            }
        } catch (Exception e) {
            logger.error("🚫 Error fetching WrapUp Name for WrapUp ID: {}: {}", wrapUpId, e.getMessage(), e);
            return null;
        }
    }






}