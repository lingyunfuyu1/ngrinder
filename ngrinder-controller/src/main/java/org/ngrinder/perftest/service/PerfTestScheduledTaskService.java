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
import org.ngrinder.model.PerfTest;
import org.ngrinder.model.Status;
import org.ngrinder.perftest.PerfTestConstants;
import org.ngrinder.perftest.PerfTestEnum;
import org.ngrinder.util.Switch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * {@link PerfTest} Service Class.
 * <p/>
 * 定时任务
 *
 * @author ziling
 * @since 3.0
 */
@Service
public class PerfTestScheduledTaskService {
	private static final Logger LOGGER = LoggerFactory.getLogger(PerfTestScheduledTaskService.class);

	@Autowired
	private PerfTestService perfTestService;

	/**
	 * 定时任务排期
	 */
//	@Scheduled(cron = "0 0 1 * * ?") //{秒} {分} {时} {日期（具体哪天）} {月} {星期}
	@Transactional
	public void generateScheduledPerfTests() {
		if (!Switch.boolean_close_gsp) {
			lockAPI();
			List<PerfTest> sourcePerfTests = perfTestService.getAllByType(PerfTestEnum.SCHEDULED_MODEL.getCode());
			Set<String> scriptNames = Sets.newHashSet();
			int i = 0;
			for (PerfTest each : sourcePerfTests) {
				// 同一个脚本可能有多个源测试任务作为模板，这里取第一个（sourcePerfTests是根据创建时间倒序排序，所以是取最新一个）
				if (scriptNames.contains(each.getScriptName())) {
					continue;
				}
				// 单个测试最长运行时间，设置计划时间需要依赖这个，确保任何时刻只有一个测试在运行
				Long maxDuaring = PerfTestConstants.ScheduledTaskParam.MAX_DURATION;
				Long duaring = each.getDuration() > maxDuaring ? maxDuaring : each.getDuration();
				Date currentTime = new Date();
				Calendar calendar = Calendar.getInstance();
				// 通过设置计划时间的先后，让多个测试串行
				calendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE) + 1 + duaring.intValue() / 1000 / 60 * i);
				Date scheduledTime = calendar.getTime();
				PerfTest perfTest = new PerfTest();
				perfTest.setTestName(each.getTestName());
				perfTest.setTagString(each.getTagString());
				perfTest.setDescription(each.getDescription());
				perfTest.setStatus(Status.READY);
				perfTest.setScheduledTime(scheduledTime);
				perfTest.setAgentCount(each.getAgentCount());
				perfTest.setVuserPerAgent(each.getVuserPerAgent());
				perfTest.setProcesses(each.getProcesses());
				perfTest.setThreads(each.getThreads());
				perfTest.setScriptName(each.getScriptName());
				perfTest.setScriptRevision(each.getScriptRevision());
				perfTest.setTargetHosts(each.getTargetHosts());
				perfTest.setThreshold(each.getThreshold());
				perfTest.setDuration(duaring);
				perfTest.setRunCount(each.getRunCount());
				perfTest.setSamplingInterval(each.getSamplingInterval());
				perfTest.setIgnoreSampleCount(each.getIgnoreSampleCount());
				perfTest.setSafeDistribution(each.getSafeDistribution());
				perfTest.setParam(each.getParam());
				perfTest.setUseRampUp(each.getUseRampUp());
				perfTest.setRampUpType(each.getRampUpType());
				perfTest.setRampUpInitCount(each.getRampUpInitCount());
				perfTest.setRampUpStep(each.getRampUpStep());
				perfTest.setRampUpInitSleepTime(each.getRampUpInitSleepTime());
				perfTest.setRampUpIncrementInterval(each.getRampUpIncrementInterval());
				perfTest.setType(PerfTestEnum.SCHEDULED_TASK.getCode());
				perfTest.setProgressMessage("");
				perfTest.setLastProgressMessage("");
				// SortedSet<Tag> tags = new TreeSet<>(each.getTags());
				perfTest.setTags(new TreeSet<>(each.getTags()));
				perfTest.setCreatedDate(currentTime);
				perfTest.setCreatedUser(each.getCreatedUser());
				perfTest.setLastModifiedDate(currentTime);
				perfTest.setLastModifiedUser(each.getLastModifiedUser());
				perfTestService.save(perfTest);
				scriptNames.add(each.getScriptName());
				i = i + 1;
			}
			unlockAPI();
		}
	}

	public void lockAPI() {
		Switch.boolean_api_lock = true;
	}

	public void unlockAPI() {
		ExecutorService executorService = Executors.newCachedThreadPool();
		executorService.execute(new Runnable() {
			@Override
			public void run() {
				try {
					TimeUnit.SECONDS.sleep(PerfTestConstants.CommonParam.WAITING_TIME);
					Switch.boolean_api_lock = false;
				} catch (InterruptedException e) {
					e.printStackTrace();
				} finally {
					LOGGER.error("API解除锁定失败！");
					Switch.boolean_api_lock = false;
				}
			}
		});
	}
}
