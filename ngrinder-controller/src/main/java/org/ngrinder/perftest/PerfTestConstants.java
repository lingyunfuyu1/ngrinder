package org.ngrinder.perftest;

public interface PerfTestConstants {

	public interface SpecialUser {
		String SCHEDULED_USER_ID = "scheduler";
	}

	public interface ScheduledTaskParam {
		Long MAX_DURATION = 15 * 60 * 1000L;
	}
}
