package com.example.Campagin.service;

import com.example.Campagin.model.Attempt;
import com.example.Campagin.model.Callback;
import com.example.Campagin.repo.AttemptRepository;
import com.example.Campagin.repo.CallbackRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.example.Campagin.config.RetryUtils.retry;

@Service
@RequiredArgsConstructor
public class TaagerService {
    private static final Logger logger = LoggerFactory.getLogger(TaagerService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AttemptRepository attemptRepository;
    private final CallbackRepository callbackRepository;

    @Value("${keycloak.token.url}")
    private String keycloakTokenUrl;

    @Value("${keycloak.client.id}")
    private String clientId;

    @Value("${keycloak.username}")
    private String username;

    @Value("${keycloak.password}")
    private String password;

    @Value("${keycloak.grant.type}")
    private String grantType;

    @Value("${taager.api.url}")
    private String taagerApiUrl;


    private String formatUtcDate(LocalDateTime localDateTime) {
        return localDateTime.atZone(ZoneId.systemDefault()) // or ZoneOffset.UTC if already UTC
                .withZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_INSTANT);
    }


    private String getAccessTokenFromKeycloak() {
        String url = keycloakTokenUrl;

        return retry(3, 5000, () -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", grantType);
            body.add("client_id", clientId);
            body.add("username", username);
            body.add("password", password);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                JsonNode json = objectMapper.readTree(response.getBody());
                return json.get("access_token").asText();
            } else {
                throw new RuntimeException("Failed to retrieve token. Status: " + response.getStatusCode());
            }
        });
    }




    public void sendAttemptsToTaager() {
        String token = getAccessTokenFromKeycloak();
        if (token == null) {
            logger.error("🚫 Cannot send attempts because no access token was retrieved.");
            return;
        }

        // 1. Fetch all callbacks
        Map<String, Callback> callbackMap = new HashMap<>();
        for (Callback cb : callbackRepository.findByStatusFalse()) {
            callbackMap.put(cb.getOutboundContactId(), cb);
        }

        // 2. Fetch all attempts
        List<Attempt> attempts = attemptRepository.findByStatusFalse();
        if (attempts.isEmpty()) {
            logger.info("No attempts found in database to send.");
            return;
        }

        // ✅ Group latest callback attempts
        Map<String, Attempt> latestCallbackAttempts = attempts.stream()
                .filter(a -> "Call Back".equals(a.getAgentWrapUpName()))
                .collect(Collectors.toMap(
                        Attempt::getOutboundContactId,
                        a -> a,
                        (a1, a2) -> a1.getStartGst().isAfter(a2.getStartGst()) ? a1 : a2
                ));

        // 3. Prepare payload
        List<Map<String, Object>> callAttempts = new ArrayList<>();
        for (Attempt attempt : attempts) {
            Map<String, Object> call = new HashMap<>();
            call.put("call_duration", attempt.getDuration() != null ? attempt.getDuration() : 0);
            call.put("call_datetime", attempt.getStartGst() != null ? formatUtcDate(attempt.getStartGst()) : "N/A");
            call.put("order_id", attempt.getOrderId() != null ? attempt.getOrderId() : "UNKNOWN");
            call.put("agent_id", attempt.getAgentEmail() != null ? attempt.getAgentEmail() : "NO_AGENT");
            call.put("Callable", attempt.isCallable());
            call.put("PhoneNumber", attempt.getPhone());

            // Wrap-up logic
            String peerWrapUp = attempt.getPeerWrapUpCode() != null ? attempt.getPeerWrapUpCode() : "NONE";
            String agentWrapUp = attempt.getAgentWrapUp() != null ? attempt.getAgentWrapUp() : "NONE";
            String agentWrapUpName = attempt.getAgentWrapUpName() != null ? attempt.getAgentWrapUpName() : "NONE";

            if (!"ININ-OUTBOUND-TRANSFERRED-TO-QUEUE".equals(peerWrapUp) && peerWrapUp.startsWith("ININ")) {
                call.put("wrap_up_reason", peerWrapUp);
            } else if ("ININ-WRAP-UP-TIMEOUT".equals(agentWrapUp)) {
                call.put("wrap_up_reason", agentWrapUp);
            } else {
                call.put("wrap_up_reason", agentWrapUpName);
            }

            // ✅ Handle callback
            if ("Call Back".equals(attempt.getAgentWrapUpName())) {
                Attempt latestAttempt = latestCallbackAttempts.get(attempt.getOutboundContactId());
                if (latestAttempt != null && latestAttempt.getCustomerSessionId().equals(attempt.getCustomerSessionId())) {
                    Callback matchingCallback = callbackMap.get(attempt.getOutboundContactId());
                    if (matchingCallback != null && matchingCallback.getCallbackScheduledGst() != null) {
                        call.put("callback_requested", true);

                        Map<String, Object> cbDetails = new HashMap<>();
                        cbDetails.put("callback_time", formatUtcDate(matchingCallback.getCallbackScheduledGst()));
                        cbDetails.put("callback_agent_id",
                                attempt.getAgentEmail() != null ? attempt.getAgentEmail() : "NO_AGENT");

                        call.put("callback_details", cbDetails);
                    }
                }
            }

            callAttempts.add(call);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("call_attempts", callAttempts);

        // Log payload
        try {
            String jsonPayload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            logger.info("📦 Final Payload Sent to Taager: \n{}", jsonPayload);
        } catch (Exception e) {
            logger.error("🚫 Failed to serialize payload to JSON", e);
        }

        // 4. Send with retry (no return value → Void)
        try {
            retry(3, 5000, () -> {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(token);

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
                String TAGGER_API_URL = taagerApiUrl;
                ResponseEntity<String> response = restTemplate.postForEntity(TAGGER_API_URL, request, String.class);

                if (response.getStatusCode().is2xxSuccessful()) {
                    logger.info("✅ Successfully sent {} attempts to Taager.", attempts.size());
                    attempts.forEach(a -> a.setStatus(true));
                    attemptRepository.saveAll(attempts);
                    callbackMap.values().forEach(cb -> cb.setStatus(true));
                    callbackRepository.saveAll(callbackMap.values());
                } else {
                    throw new RuntimeException("🚫 Failed to send attempts. Status: " + response.getStatusCode() +
                            ", Body: " + response.getBody());
                }

                return null; // ✅ important, because retry expects a return
            });
        } catch (Exception e) {
            logger.error("🚫 Error while sending attempts to Taager after retries: {}", e.getMessage(), e);
        }
    }








}
