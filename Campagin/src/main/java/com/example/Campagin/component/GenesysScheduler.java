package com.example.Campagin.component;

import com.example.Campagin.service.GenesysService;
import com.example.Campagin.service.TaggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class GenesysScheduler {

    private final GenesysService genesysService;
    private final TaggerService taggerService;

    private static final Logger logger = LoggerFactory.getLogger(GenesysScheduler.class);

    // constructor
    public GenesysScheduler(GenesysService genesysService, TaggerService taggerService) {
        this.genesysService = genesysService;
        this.taggerService = taggerService;
    }

    // Run every 10 minutes (60000 ms)
    @Scheduled(fixedRate = 60000)
    public void runFullGenesysPipelineJob() {
        logger.info("🚀 Scheduler: Starting full Genesys sync and processing pipeline...");
        genesysService.saveAllWrapUpCodes();
        genesysService.saveNewUsersFromSearchApi();
        genesysService.getConversationsAndStore();
        taggerService.sendAttemptsToTaager();


    }
}
