package app.bepro.digest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import app.bepro.config.BeproProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** In-process weekly trigger for always-on deployments. Cloud Run uses Cloud Scheduler instead. */
@Component
@ConditionalOnProperty(name = "bepro.digest.scheduler-enabled", havingValue = "true")
class DigestScheduler {

	private static final Logger log = LoggerFactory.getLogger(DigestScheduler.class);

	private final DigestService digests;

	private final BeproProperties properties;

	DigestScheduler(DigestService digests, BeproProperties properties) {
		this.digests = digests;
		this.properties = properties;
	}

	@Scheduled(cron = "${bepro.digest.cron}", zone = "${bepro.zone}")
	void weekly() {
		log.info("Weekly digest: {}", digests.run(properties.userId(), false));
	}

}
