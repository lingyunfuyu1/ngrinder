/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ngrinder.perftest.service;

import com.google.common.collect.Sets;
import org.ngrinder.common.constant.ScheduleConstants;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.model.PerfTest;
import org.ngrinder.model.Status;
import org.ngrinder.model.Type;
import org.ngrinder.model.User;
import org.ngrinder.user.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * {@link PerfTest} Service Class.
 * <p/>
 * 定时任务
 *
 * @author ziling
 * @since 3.0
 */
@Service
public class PerfTestScheduleService {

	@Autowired
	private PerfTestService perfTestService;

	@Autowired
	private UserService userService;

	@Autowired
	private Config config;

	/**
	 * 定时任务排期
	 *
	 * @Scheduled(cron = "0 0 1 * * ?") //{秒} {分} {时} {日期（具体哪天）} {月} {星期}
	 */
	@Transactional(rollbackFor = Exception.class)
	public void batchGenerateSchedulePerfTest() {
		if (!config.getScheduleProperties().getPropertyBoolean(ScheduleConstants.SCHEDULE_PROP_CLOSE)) {
			CoreLogger.LOGGER.warn("定时任务处于关闭状态，将不会执行业务逻辑。");
			return;
		}
		// 定时任务默认用户
		User user = userService.getOne(ScheduleConstants.SCHEDULE_PROP_DEFAULT_USERID);
		// TODO：测试任务源数据，目前根据type=1（scheduled_model）来获取
		List<PerfTest> sourcePerfTests = perfTestService.getAll(Type.TEMPLATE);
		Set<String> scriptNames = Sets.newHashSet();
		// 两次测试之间的停顿时间
		long waitingTime = 60 * 1000;
		Long scheduledTimeMillis = System.currentTimeMillis() + waitingTime;
		for (PerfTest each : sourcePerfTests) {
			// 同一个脚本可能有多个源测试任务作为模板，这里取第一个（sourcePerfTests是根据创建时间倒序排序，所以是取最新一个）
			if (scriptNames.contains(each.getScriptName())) {
				continue;
			}
			Date scheduledTime = new Date(Long.parseLong(String.valueOf(scheduledTimeMillis)));
			Long duaring = each.getDuration();
			Long maxDuaring = 1000 * 60 * config.getScheduleProperties().getPropertyLong(ScheduleConstants.SCHEDULE_PROP_MAX_DURATION);
			if (duaring > maxDuaring) {
				CoreLogger.LOGGER.warn("运行时长超过最大限制，自动设置为默认值" + maxDuaring + "分钟");
				duaring = maxDuaring;
			}
			generateSchedulePerfTest(user, each, scheduledTime, duaring);
			scriptNames.add(each.getScriptName());
			// 下个任务的计划时间 = 当前任务的计划时间 + 运行时长 + 两次任务的间隔时间
			scheduledTimeMillis = scheduledTimeMillis + duaring + waitingTime;
		}

	}

	/**
	 * 根据源测试生成定时任务测试
	 *
	 * @param user           user
	 * @param sourcePerfTest sourcePerfTest
	 * @param scheduledTime  scheduledTime
	 * @param scheduledTime  scheduledTime
	 * @return PerfTest
	 */
	private void generateSchedulePerfTest(User user, PerfTest sourcePerfTest, Date scheduledTime, Long duaring) {
		PerfTest perfTest = copyBasicAttributes(sourcePerfTest);
		perfTest.setType(Type.TASK);
		perfTest.setTagString(Type.TASK.toString().toLowerCase());
		perfTest.setStatus(Status.READY);
		perfTest.setScheduledTime(scheduledTime);
		perfTest.setDuration(duaring);
		perfTest.setProgressMessage("");
		perfTest.setLastProgressMessage("");
		Date currentTime = new Date();
		perfTest.setCreatedDate(currentTime);
		perfTest.setLastModifiedDate(currentTime);
		perfTestService.save(user, perfTest);
	}

	/**
	 * 将一个PerfTest实例中的基本用户设置属性拷贝到一个新的PerfTest实例中
	 *
	 * @param sourcePerfTest sourcePerfTest
	 * @return PerfTest
	 */
	private PerfTest copyBasicAttributes(PerfTest sourcePerfTest) {
		PerfTest perfTest = new PerfTest();
		perfTest.setTestName(sourcePerfTest.getTestName());
		//perfTest.setTagString(sourcePerfTest.getTagString());
		perfTest.setDescription(sourcePerfTest.getDescription());
		perfTest.setAgentCount(sourcePerfTest.getAgentCount());
		perfTest.setVuserPerAgent(sourcePerfTest.getVuserPerAgent());
		perfTest.setProcesses(sourcePerfTest.getProcesses());
		perfTest.setThreads(sourcePerfTest.getThreads());
		perfTest.setScriptName(sourcePerfTest.getScriptName());
		perfTest.setScriptRevision(sourcePerfTest.getScriptRevision());
		perfTest.setTargetHosts(sourcePerfTest.getTargetHosts());
		perfTest.setThreshold(sourcePerfTest.getThreshold());
		perfTest.setRunCount(sourcePerfTest.getRunCount());
		perfTest.setSamplingInterval(sourcePerfTest.getSamplingInterval());
		perfTest.setIgnoreSampleCount(sourcePerfTest.getIgnoreSampleCount());
		perfTest.setSafeDistribution(sourcePerfTest.getSafeDistribution());
		perfTest.setParam(sourcePerfTest.getParam());
		perfTest.setUseRampUp(sourcePerfTest.getUseRampUp());
		perfTest.setRampUpType(sourcePerfTest.getRampUpType());
		perfTest.setRampUpInitCount(sourcePerfTest.getRampUpInitCount());
		perfTest.setRampUpStep(sourcePerfTest.getRampUpStep());
		perfTest.setRampUpInitSleepTime(sourcePerfTest.getRampUpInitSleepTime());
		perfTest.setRampUpIncrementInterval(sourcePerfTest.getRampUpIncrementInterval());
		//perfTest.setTags(new TreeSet<>(sourcePerfTest.getTags()));
		perfTest.setCreatedUser(sourcePerfTest.getCreatedUser());
		perfTest.setLastModifiedUser(sourcePerfTest.getLastModifiedUser());
		return perfTest;
	}

}
