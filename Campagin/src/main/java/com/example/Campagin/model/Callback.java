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
public class Callback {

    @Id
    private String conversationId;

    private String outboundContactId;
    private LocalDateTime callbackScheduledGst;
    private String callbackNumbers; // Store as a comma-separated string
private  boolean status=false;
    // Getters and Setters
}