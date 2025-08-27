package com.example.Campagin.component;

import com.example.Campagin.service.GenesysService;
import com.example.Campagin.service.TaggerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;

@Component
public class GenesysScheduler {

    private final GenesysService genesysService;
    private final TaggerService taggerService;
    private static final Logger logger = LoggerFactory.getLogger(GenesysScheduler.class);

    private final ReentrantLock pipeline1Lock = new ReentrantLock();
    private final ReentrantLock pipeline2Lock = new ReentrantLock();
    private final ReentrantLock pipeline3Lock = new ReentrantLock();

    public GenesysScheduler(GenesysService genesysService, TaggerService taggerService) {
        this.genesysService = genesysService;
        this.taggerService = taggerService;
    }

    /** Pipeline1: Wrap-up codes + New Users كل ساعة */
    @Scheduled(fixedRate = 60 * 60 * 1000, initialDelay = 0)
    public void firstPipeline() {
        if (!pipeline1Lock.tryLock()) {
            logger.info("⏳ Pipeline1 skipped: another process is running.");
            return;
        }
        try {
            runStep("Fetching and saving wrap-up codes", genesysService::saveAllWrapUpCodes);
            runStep("Fetching and saving new users", genesysService::saveNewUsersFromSearchApi);
        } finally {
            pipeline1Lock.unlock();
        }
    }

    /** Pipeline2: Conversations + Taager كل 1 دقائق */
    @Scheduled(fixedRate =  60 * 1000, initialDelay = 30* 1000)
    public void secondPipeline() {
        if (!pipeline2Lock.tryLock()) {
            logger.info("⏳ Pipeline2 skipped: another process is running.");
            return;
        }
        try {
            runStep("Fetching and storing conversations", genesysService::getConversationsAndStore);

            // ✅ Delay between fetch and send (مثلاً 5 ثواني)
            try {
                logger.info("⏳ Waiting 30 seconds before sending attempts to Taager...");
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("⚠️ Delay interrupted before sending to Taager");
            }

            runStep("Sending attempts to Taager", taggerService::sendAttemptsToTaager);
        } finally {
            pipeline2Lock.unlock();
        }
    }

    /** Pipeline3: Delete old attempts كل 9 دقائق */
    @Scheduled(cron = "0 2 12 * * ?")
    public void deleteOldAttemptsJob() {
        if (!pipeline3Lock.tryLock()) {
            logger.info("⏳ Pipeline3 skipped: another process is running.");
            return;
        }
        try {
            runStep("Deleting old attempts", genesysService::deleteOldAttempts);
        } finally {
            pipeline3Lock.unlock();
        }
    }

    /** Helper method to wrap steps with logging and error handling */
    private void runStep(String description, Runnable step) {
        logger.info("🚀 Step started: {}", description);
        try {
            step.run();
            logger.info("✅ Step completed successfully: {}", description);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.error("❌ Step failed: {}.", description, e);
            } else {
                logger.error("❌ Step failed: {} - {}", description, e.getMessage());
            }
        }
    }
}
