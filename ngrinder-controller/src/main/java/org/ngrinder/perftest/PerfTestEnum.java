package org.ngrinder.perftest;

import java.io.Serializable;

public enum PerfTestEnum implements Serializable {


	DEFAULT(0, "default", "默认"),
	SCHEDULED_MODEL(1, "scheduled_model", "计划模板"),
	SCHEDULED_TASK(2, "scheduled_task", "计划任务");

	private static final String SCHEDULED_USER_ID = "scheduler";

	private Integer code;
	private String value;
	private String description;

	PerfTestEnum(Integer code, String value, String description) {
		this.code = code;
		this.value = value;
		this.description = description;
	}

	public Integer getCode() {
		return this.code;
	}

	public String getValue() {
		return this.value;
	}

	public String getDescription() {
		return this.description;
	}

	/**
	 * 根据code获取PerfTestEnum
	 *
	 * @param code Integer code
	 * @return PerfTestConstants
	 */
	public static PerfTestEnum getValue(Integer code) {
		for (PerfTestEnum each : PerfTestEnum.values()) {
			if (each.getCode().equals(code)) {
				return each;
			}
		}
		return null;
	}
}
