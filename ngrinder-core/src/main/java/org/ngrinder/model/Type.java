package org.ngrinder.model;

/**
 * Type model.
 *
 * @author ziling
 * @since 3.3
 */

public enum Type {
	DEFAULT("default"),
	TEMPLATE("template"),
	TASK("task");

	private String messageKey;

	Type(String messageKey) {
		this.messageKey = messageKey;
	}

	public String getMessageKey() {
		return this.messageKey;
	}

}
