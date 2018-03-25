package org.ngrinder.util;

import org.springframework.beans.factory.InitializingBean;

public class Switch implements InitializingBean {
	/**
	 * 关闭定时任务
	 */
	public volatile static boolean boolean_close_gsp = false;

	/**
	 * api接口防刷锁
	 */
	public volatile static boolean boolean_api_lock = false;


	@Override
	public void afterPropertiesSet() {
	}
}
