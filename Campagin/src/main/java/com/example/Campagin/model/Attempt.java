package com.example.Campagin.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Attempt {

    @Id
    private String customerSessionId;

    private LocalDateTime startGst;
    private LocalDateTime endGst;
    private Integer dialSeconds;
    private Double talkSeconds;
    private String outcome;
    private String disconnect;
    private String sip;
    private String outboundContactId;
    private String peerSessionId;
    private String peerPurpose;
    private String peerDisposition;
    private String peerAnalyzer;
    private String peerWrapUpCode;
    private String peerSip;
    private String peerProtocolCallId;
    private String peerSessionDnis;
    private String peerProvider;
    private String agentSessionId;
    private String agentUserId;
    private Double agentAlertSeconds;
    private Double agentAnsweredSeconds;
    private Double agentTalkSeconds;
    private Double agentHoldSeconds;
    private Double agentAcwSeconds;
    private Double agentHandleSeconds;
    private String agentWrapUp;
    private String agentEmail;
    private Double duration;
    private String phone;
    private String orderId;
    private  String campaignId;
    private String conversationId;
   private boolean status;
    private String agentWrapUpName;

    // Getters and Setters
}