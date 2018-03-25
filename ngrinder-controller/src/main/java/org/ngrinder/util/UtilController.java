package org.ngrinder.util;

import org.ngrinder.common.constant.ControllerConstants;
import org.ngrinder.common.controller.BaseController;
import org.ngrinder.common.controller.RestAPI;
import org.ngrinder.model.User;
import org.ngrinder.perftest.PerfTestConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/util/api")
public class UtilController extends BaseController implements ControllerConstants {
	private static final Logger LOG = LoggerFactory.getLogger(org.ngrinder.home.controller.HomeController.class);

	/**
	 * 开启或关闭定时任务
	 *
	 * @param close close
	 * @return
	 */
	@RestAPI
	@RequestMapping(value = {"/gsp"})
	public HttpEntity<String> gspSwitch(User user, @RequestParam(value = "close") Boolean close) {
		if (!PerfTestConstants.SpecialUser.SCHEDULED_USER_ID.equals(user.getUserId())) {
			return toJsonHttpEntity("[FAILED] 操作失败，只有scheduler用户有这个权限!");
		}
		if (close == Switch.boolean_close_gsp) {
			return toJsonHttpEntity("[WARN] 操作无效，修改前后值一样! 当前值为" + Switch.boolean_close_gsp);
		}
		Switch.boolean_close_gsp = close;
		return toJsonHttpEntity("[SUCCESS] 操作成功。当前值为" + Switch.boolean_close_gsp);
	}

	/**
	 * 定时任务接口是否开放
	 *
	 * @return
	 */
	@RestAPI
	@RequestMapping(value = {"/gsp/status"})
	public HttpEntity<String> gspStatus(User user) {
		if (!PerfTestConstants.SpecialUser.SCHEDULED_USER_ID.equals(user.getUserId())) {
			return toJsonHttpEntity("[FAILED] 操作失败，只有scheduler用户有这个权限!");
		}
		if (Switch.boolean_close_gsp) {
			return toJsonHttpEntity("[INFO] 当前接口处于【关闭】状态，禁止调用。");
		} else {
			return toJsonHttpEntity("[INFO] 当前接口处于【开放】状态，可以调用。");
		}
	}
}
