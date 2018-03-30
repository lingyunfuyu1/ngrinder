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

import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.grinder.SingleConsole;
import net.grinder.StopReason;
import net.grinder.common.GrinderProperties;
import net.grinder.console.communication.AgentProcessControlImplementation.AgentStatus;
import net.grinder.console.model.ConsoleProperties;
import net.grinder.util.ConsolePropertiesFactory;
import net.grinder.util.Directory;
import net.grinder.util.Pair;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.Predicate;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.hibernate.Hibernate;
import org.ngrinder.common.constant.ControllerConstants;
import org.ngrinder.common.constants.GrinderConstants;
import org.ngrinder.infra.config.Config;
import org.ngrinder.infra.logger.CoreLogger;
import org.ngrinder.model.*;
import org.ngrinder.monitor.controller.model.SystemDataModel;
import org.ngrinder.perftest.controller.PerfTestController;
import org.ngrinder.perftest.model.PerfTestStatistics;
import org.ngrinder.perftest.model.ProcessCountAndThreadCount;
import org.ngrinder.perftest.repository.PerfTestRepository;
import org.ngrinder.perftest.service.samplinglistener.MonitorCollectorPlugin;
import org.ngrinder.perftest.service.samplinglistener.PerfTestSamplingCollectorListener;
import org.ngrinder.script.handler.NullScriptHandler;
import org.ngrinder.script.handler.ProcessingResultPrintStream;
import org.ngrinder.script.handler.ScriptHandler;
import org.ngrinder.script.handler.ScriptHandlerFactory;
import org.ngrinder.script.model.FileEntry;
import org.ngrinder.script.service.FileEntryService;
import org.ngrinder.service.AbstractPerfTestService;
import org.ngrinder.user.service.UserService;
import org.python.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specifications;
import org.springframework.transaction.annotation.Transactional;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import static org.ngrinder.common.constants.MonitorConstants.MONITOR_FILE_PREFIX;
import static org.ngrinder.common.util.AccessUtils.getSafe;
import static org.ngrinder.common.util.CollectionUtils.*;
import static org.ngrinder.common.util.ExceptionUtils.processException;
import static org.ngrinder.common.util.NoOp.noOp;
import static org.ngrinder.common.util.Preconditions.checkNotEmpty;
import static org.ngrinder.common.util.Preconditions.checkNotNull;
import static org.ngrinder.model.Status.getProcessingOrTestingTestStatus;
import static org.ngrinder.perftest.repository.PerfTestSpecification.*;

/**
 * {@link PerfTest} Service Class.
 * <p/>
 * This class contains various method which mainly get the {@link PerfTest} matching specific conditions from DB.
 *
 * @author JunHo Yoon
 * @author Mavlarn
 * @since 3.0
 */
public class PerfTestService extends AbstractPerfTestService implements ControllerConstants, GrinderConstants {
	private static final Logger LOGGER = LoggerFactory.getLogger(PerfTestService.class);

	private static final int MAX_POINT_COUNT = 100;

	private static final String DATA_FILE_EXTENSION = ".data";

	@Autowired
	private PerfTestRepository perfTestRepository;

	@Autowired
	private ConsoleManager consoleManager;

	@Autowired
	private AgentManager agentManager;

	@Autowired
	private Config config;

	@Autowired
	private FileEntryService fileEntryService;

	@Autowired
	private TagService tagService;

	@Autowired
	private ScriptHandlerFactory scriptHandlerFactory;

	/**
	 * 根据指定的查询条件的获取Page<PerfTest>（分页列表）.
	 * {@link PerfTestService#getPagedAll(org.ngrinder.model.User, java.lang.String, java.lang.String, java.lang.String, org.springframework.data.domain.Pageable)}
	 * {@link PerfTestController#getAll(org.ngrinder.model.User, java.lang.String, java.lang.String, java.lang.String, org.springframework.data.domain.Pageable, org.springframework.ui.ModelMap)}
	 * {@link PerfTestController#getAll(org.ngrinder.model.User, int, int)}
	 *
	 * @param user        user
	 * @param query       测试名称或描述包含的字符串，对应于页面的Keywords
	 * @param tag         tag
	 * @param queryFilter R-正在运行的测试，S-已预约的测试, F-已结束的测试
	 * @param pageable    paging info
	 * @return found {@link PerfTest} list
	 */
	public Page<PerfTest> getPagedAll(User user, String query, String tag, String queryFilter, Pageable pageable) {
		// Specifications动态构建查询语句
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());
		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user.getRole().equals(Role.GENERAL_USER)) {
			spec = spec.and(createdBy(user));
		}

		// 包含指定tag
		if (StringUtils.isNotBlank(tag)) {
			spec = spec.and(hasTag(tag));
		}
		// 已结束、正在运行、已预约 and status = 'FINISHED/TESTING/READY'
		if ("F".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.FINISHED));
		} else if ("R".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.TESTING));
		} else if ("S".equals(queryFilter)) {
			spec = spec.and(statusSetEqual(Status.READY));
			spec = spec.and(scheduledTimeNotEmptyPredicate());
		}else if ("SX".equals(queryFilter)) {
			spec = spec.and(typeEqual(Type.TEMPLATE));
		}
		// and (name like '%{query}%' or description like '%{query}%')
		if (StringUtils.isNotBlank(query)) {
			spec = spec.and(likeTestNameOrDescription(query));
		}
		return perfTestRepository.findAll(spec, pageable);
	}

	/**
	 * 根据指定的user获取List<PerfTest>
	 * {@link PerfTestService#deleteAll(org.ngrinder.model.User)}
	 */
	List<PerfTest> getAll(User user) {
		Specifications<PerfTest> spec = Specifications.where(createdBy(user));
		return perfTestRepository.findAll(spec);
	}

	/**
	 * 根据指定的id获取PerfTest，同时加载tags
	 * {@link PerfTestController#getOneWithPermissionCheck(org.ngrinder.model.User, java.lang.Long, boolean)}
	 *
	 * @param testId PerfTest id
	 * @return
	 */
	@Transactional
	@Override
	public PerfTest getOneWithTag(Long testId) {
		PerfTest perfTest = getOne(testId);
		if (perfTest != null) {
			// Hibernate.initialize(Object proxy) 强制加载对象tags（类型：SortedSet<Tag>）
			Hibernate.initialize(perfTest.getTags());
		}
		return perfTest;
	}

	/**
	 * 根据指定的id获取PerfTest
	 *
	 * @param testId PerfTest id
	 * @return
	 */
	@Override
	public PerfTest getOne(Long testId) {
		return perfTestRepository.findOne(testId);
	}

	/**
	 * 根据指定的user和id获取PerfTest
	 */
	@Override
	public PerfTest getOne(User user, Long id) {
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());

		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user.getRole().equals(Role.GENERAL_USER)) {
			spec = spec.and(createdBy(user));
		}
		spec = spec.and(idEqual(id));
		return perfTestRepository.findOne(spec);
	}

	/**
	 * 根据指定的user和statuses获取List<PerfTest>.size()
	 */
	@Override
	public long count(User user, Status[] statuses) {
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());

		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user != null) {
			spec = spec.and(createdBy(user));
		}

		if (statuses.length == 0) {
			return 0;
		} else {
			// and status in {statuses}
			return perfTestRepository.count(spec.and(statusSetEqual(statuses)));
		}

	}

	/**
	 * 根据指定的user和ids获取List<PerfTest>
	 */
	@Override
	public List<PerfTest> getAll(User user, Long[] ids) {
		if (ids.length == 0) {
			return newArrayList();
		}
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());

		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user.getRole().equals(Role.GENERAL_USER)) {
			spec = spec.and(createdBy(user));
		}
		// and id in {ids}
		spec = spec.and(idSetEqual(ids));
		return perfTestRepository.findAll(spec);
	}

	/**
	 * 根据指定的user和statuses获取List<PerfTest>
	 */
	@Override
	public List<PerfTest> getAll(User user, Status[] statuses) {
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());

		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user != null) {
			spec = spec.and(createdBy(user));
		}
		if (statuses.length != 0) {
			spec = spec.and(statusSetEqual(statuses));
		}

		return perfTestRepository.findAll(spec);
	}

	/**
	 * 根据指定的user、region和statuses获取List<PerfTest>.
	 */
	private List<PerfTest> getAll(User user, String region, Status[] statuses) {
		Specifications<PerfTest> spec = Specifications.where(idEmptyPredicate());
		// 如果角色是GENERAL_USER，则需要增加createdUser条件，确保只能查到用户自己创建的测试；如果角色是ADMIN、SUPER_USER等其他，则无需设置此条件，可以查询所有用户创建的测试
		if (user != null) {
			spec = spec.and(createdBy(user));
		}
		// 只有在cluster（集群）模式时才会设置region字段
		if (config.isClustered()) {
			spec = spec.and(idRegionEqual(region));
		}
		if (statuses.length != 0) {
			spec = spec.and(statusSetEqual(statuses));
		}

		return perfTestRepository.findAll(spec);
	}

	/**
	 * 根据创建时间获取List<PerfTest>
	 *
	 * @param start start time.
	 * @param end   end time.
	 * @return
	 */
	@Override
	public List<PerfTest> getAll(Date start, Date end) {
		return perfTestRepository.findAllByCreatedTime(start, end);
	}

	/**
	 * 根据创建时间和地区获取List<PerfTest>
	 *
	 * @param start  start time.
	 * @param end    end time.
	 * @param region region
	 * @return
	 */
	@Override
	public List<PerfTest> getAll(Date start, Date end, String region) {
		return perfTestRepository.findAllByCreatedTimeAndRegion(start, end, region);
	}

	/**
	 * Get {@link PerfTest} list for the given user.
	 *
	 * @param type search type.
	 * @return found {@link PerfTest} list
	 */
	public List<PerfTest> getAll(Type type) {
		return perfTestRepository.findAllByTypeOrderByCreatedTimeDesc(type);
	}

	/**
	 * 获取所有测试的列表.
	 */
	@Override
	public List<PerfTest> getAllPerfTest() {
		return perfTestRepository.findAll();
	}

	/**
	 * 获取下一个可运行的候选PerfTest.
	 *
	 * @return found {@link PerfTest} which is ready to run, null otherwise
	 */
	@Transactional
	public PerfTest getNextRunnablePerfTestCandidate() {
		// 取到所有READY状态的perfTest，按计划时间升序排列
		List<PerfTest> readyPerfTests = perfTestRepository.findAllByStatusOrderByScheduledTimeAsc(Status.READY);
		// 过滤掉已经在运行某个测试的User的readyPerfTests，只留下未运行任何测试的User的readyPerfTests
		List<PerfTest> usersFirstPerfTests = filterCurrentlyRunningTestUsersTest(readyPerfTests);
		// 如果不为空，取第一个，也就是scheduledTime
		return usersFirstPerfTests.isEmpty() ? null : readyPerfTests.get(0);
	}

	/**
	 * 获取不正常的PerfTest列表
	 *
	 * @return running test list
	 */
	public List<PerfTest> getAllAbnormalTesting() {
		return getAll(null, config.getRegion(), new Status[]{Status.ABNORMAL_TESTING});
	}

	/**
	 * 获取PROGRESSING和TESTING的PerfTest列表
	 * each.getCategory() == StatusCategory.PROGRESSING || each.getCategory() == StatusCategory.TESTING)
	 *
	 * @return running test list
	 */
	@Override
	public List<PerfTest> getAllTesting() {
		return getAll(null, config.getRegion(), Status.getTestingTestStates());
	}

	/**
	 * 获取TESTING的PerfTest列表
	 * each.getCategory() == StatusCategory.TESTING)
	 *
	 * @return running test list
	 */
	public List<PerfTest> getCurrentlyRunningTest() {
		// status.getCategory() == StatusCategory.PROGRESSING || status.getCategory() == StatusCategory.TESTING
		return getAll(null, Status.getProcessingOrTestingTestStatus());
	}

	/**
	 * 运行测试的用户的所有的测试，都过滤掉；只留下没有运行任何测试的用户的测试列表
	 *
	 * @param perfTestLists perf test
	 * @return filtered perf test
	 */
	protected List<PerfTest> filterCurrentlyRunningTestUsersTest(List<PerfTest> perfTestLists) {
		// 获取到当前正在运行的测试
		List<PerfTest> currentlyRunningTests = getCurrentlyRunningTest();
		final Set<User> currentlyRunningTestOwners = newHashSet();
		for (PerfTest each : currentlyRunningTests) {
			// 将正在运行的测试的用户（创建者）加到一个Set
			currentlyRunningTestOwners.add(each.getCreatedUser());
		}
		// 过滤掉perfTestLists中用户（创建者）属于上面Set中的perfTest
		CollectionUtils.filter(perfTestLists, new Predicate() {
			@Override
			public boolean evaluate(Object object) {
				PerfTest perfTest = (PerfTest) object;
				return !currentlyRunningTestOwners.contains(perfTest.getCreatedUser());
			}
		});
		// 返回没有运行任何测试的用户（创建者）的perfTestLists
		return perfTestLists;
	}

	/**
	 * PerfTest附上revision、type、tags后保存入库
	 */
	@Override
	@Transactional
	public PerfTest save(User user, PerfTest perfTest) {
		// 附加revision字段
		attachFileRevision(user, perfTest);
		// 附加type字段
		attachType(perfTest);
		// 附加tagString、tags字段
		attachTags(user, perfTest, perfTest.getTagString());
		return save(perfTest);

	}

	/**
	 * PerfTest保存入库
	 */
	private PerfTest save(PerfTest perfTest) {
		checkNotNull(perfTest);
		// 如果id不为空且不等于0，则执行merge
		if (perfTest.exist()) {
			PerfTest existingPerfTest = perfTestRepository.findOne(perfTest.getId());
			perfTest = existingPerfTest.merge(perfTest);
		} else {
			// 清空消息
			perfTest.clearMessages();
		}
		// 这里saveAndFlush才会生成testId
		return perfTestRepository.saveAndFlush(perfTest);
	}

	/**
	 * 设置版本号，存最新SVN版本号的值（如果没有取到，则为-1）
	 * @param user
	 * @param perfTest
	 */
	private void attachFileRevision(User user, PerfTest perfTest) {
		if (perfTest.getStatus() == Status.READY) {
			// 从SVN上获取脚本的最新版本文件
			FileEntry scriptEntry = fileEntryService.getOne(user, perfTest.getScriptName());
			// 如果脚本文件有SVN版本号，则取到版本号数值；如果没有SVN版本号，则设置为-1
			long revision = scriptEntry != null ? scriptEntry.getRevision() : -1;
			perfTest.setScriptRevision(revision);
		}
	}

	/**
	 * 设置type字段，【特定用户】可以前台修改为type=1（模板），后台定时任务默认type=2（后台定时任务），为空时type=0
	 *
	 * @param perfTest      perfTest
	 */
	private void attachType(PerfTest perfTest) {
		if (perfTest.getType() == null) {
			perfTest.setType(Type.DEFAULT);
		}
	}

	/**
	 * 设置tagString和tags字段，其中tags是根据tagString生成
	 *
	 * @param user
	 * @param perfTest
	 * @param tagString
	 */
	private void attachTags(User user, PerfTest perfTest, String tagString) {
		String[] tagArray = StringUtils.split(StringUtils.trimToEmpty(tagString), ",");
		// TODO：这里标签入口貌似不是实时的，可能和事务有关？@Transactional
		SortedSet<Tag> tags = tagService.addTags(user, tagArray);
		perfTest.setTags(tags);
		perfTest.setTagString(buildTagString(tags));
	}

	/**
	 * 返回tag列表的字符串格式，列表元素直接用逗号分隔，比如 tags = {tag1, tag2, tag3} , tagString = "tag1,tag2,tag2"
	 * @param tags
	 * @return
	 */
	private String buildTagString(Set<Tag> tags) {
		List<String> tagStringResult = new ArrayList<>();
		for (Tag each : tags) {
			tagStringResult.add(each.getTagValue());
		}
		return StringUtils.join(tagStringResult, ",");
	}

	/**
	 * Delete {@link PerfTest} directory.
	 *
	 * @param perfTest perfTest
	 */
	private void deletePerfTestDirectory(PerfTest perfTest) {
		FileUtils.deleteQuietly(config.getHome().getPerfTestDirectory(perfTest));
	}

	/**
	 * 根据User和testId删除PerfTest
	 *
	 * @param user user
	 * @param id   test id
	 */
	@Transactional(rollbackFor = Exception.class)
	public void delete(User user, long id) {
		PerfTest perfTest = getOne(id);
		// 权限校验
		if (!hasPermission(perfTest, user, Permission.DELETE_TEST_OF_OTHER)) {
			return;
		}
		SortedSet<Tag> tags = perfTest.getTags();
		if (tags != null) {
			tags.clear();
		}
		perfTestRepository.save(perfTest);
		perfTestRepository.delete(perfTest);
		deletePerfTestDirectory(perfTest);
	}

	/**
	 * 删除给定用户的所有PerfTest和相关的tags，删除账号时调用
	 * {@link UserService#delete(java.lang.String)}
	 *
	 * @param user user
	 * @return deleted {@link PerfTest} list
	 */
	@Transactional
	public List<PerfTest> deleteAll(User user) {
		List<PerfTest> perfTestList = getAll(user);
		for (PerfTest each : perfTestList) {
			each.getTags().clear();
		}
		//先删除PerfTest和tag的关联关系
		perfTestRepository.save(perfTestList);
		perfTestRepository.flush();
		//再删除PerfTest
		perfTestRepository.delete(perfTestList);
		perfTestRepository.flush();
		//最后删除tags
		tagService.deleteTags(user);
		return perfTestList;
	}


	/**
	 * 判断用户User是否对该测试PerfTest拥有某种权限Permission
	 *
	 * @param perfTest   perf test
	 * @param user       user
	 * @param permission permission to check
	 * @return true if it has
	 */
	private boolean hasPermission(PerfTest perfTest, User user, Permission permission) {
		return perfTest != null && (user.getRole().hasPermission(permission) || user.equals(perfTest.getCreatedUser()));
	}

	/**
	 * 停止测试
	 *
	 * @param user user
	 * @param id   perftest id
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void stop(User user, Long id) {
		PerfTest perfTest = getOne(id);
		// If it's not requested by user who started job. It's wrong request.
		if (!hasPermission(perfTest, user, Permission.STOP_TEST_OF_OTHER)) {
			return;
		}
		// If it's not stoppable status.. It's wrong request.
		if (!perfTest.getStatus().isStoppable()) {
			return;
		}
		// Just mark cancel on console
		// This will be not be effective on cluster mode.
		consoleManager.getConsoleUsingPort(perfTest.getPort()).cancel();
		perfTest.setStopRequest(true);
		perfTestRepository.save(perfTest);
	}

	/**
	 * 获取代理状态信息的JSON字符串格式，如果超长，超过的部分被截断
	 * {@link PerfTestService#getProperSizedStatusString(net.grinder.SingleConsole)}
	 *
	 * @param agentStatusMap agentStatusMap
	 */
	String getProperSizedStatusString(Map<String, SystemDataModel> agentStatusMap) {
		String json = gson.toJson(agentStatusMap);
		int statusLength = StringUtils.length(json);
		// 状态信息超长时截断处理（max column size is 10000）
		if (statusLength > 9950) {
			LOGGER.info("Agent status string length: {}, too long to save into table.", statusLength);
			// 计算比率
			double ratio = 9900.0 / statusLength;
			// 选中的size
			int pickSize = (int) (agentStatusMap.size() * ratio);
			Map<String, SystemDataModel> pickAgentStateMap = Maps.newHashMap();
			int pickIndex = 0;
			for (Entry<String, SystemDataModel> each : agentStatusMap.entrySet()) {
				// pickSize之外的部分被舍弃
				if (pickIndex < pickSize) {
					pickAgentStateMap.put(each.getKey(), each.getValue());
					pickIndex++;
				}
			}
			json = gson.toJson(pickAgentStateMap);
			LOGGER.debug("Agent status string get {} outof {} agents, new size is {}.", new Object[]{pickSize, agentStatusMap.size(), json.length()});
		}
		return json;
	}


	/**
	 * 从singleConsole获取代理状态信息的JSON字符串格式，如果超长，超过的部分被截断
	 * Get the limited size of agent status json string.
	 * {@link PerfTestService#saveStatistics(net.grinder.SingleConsole, java.lang.Long)}
	 *
	 * @param singleConsole
	 */
	public String getProperSizedStatusString(SingleConsole singleConsole) {
		Map<String, SystemDataModel> agentStatusMap = Maps.newHashMap();
		final int singleConsolePort = singleConsole.getConsolePort();
		// 从SingleConsole获取AgentStatusMap
		for (AgentStatus each : agentManager.getAgentStatusSetConnectingToPort(singleConsolePort)) {
			agentStatusMap.put(each.getAgentName(), each.getSystemDataModel());
		}
		return getProperSizedStatusString(agentStatusMap);
	}

	/**
	 * 从singleConsole获取运行采样信息的字符串格式，超长部分会被截断
	 * {@link PerfTestService#saveStatistics(net.grinder.SingleConsole, java.lang.Long)}
	 *
	 * @param singleConsole singleConsole
	 */
	private String getProperSizeRunningSample(SingleConsole singleConsole) {
		Map<String, Object> statisticData = singleConsole.getStatisticsData();
		String runningSample = gson.toJson(statisticData);
		// max column size is 10000
		if (runningSample.length() > 9950) {
			Map<String, Object> tempData = newHashMap();
			for (Entry<String, Object> each : statisticData.entrySet()) {
				String key = each.getKey();
				if ("totalStatistics".equals(key) || "cumulativeStatistics".equals(key) || "lastSampleStatistics".equals(key)) {
					continue;
				}
				tempData.put(key, each.getValue());
			}
			runningSample = gson.toJson(tempData);
		}
		return runningSample;
	}

	/**
	 * 更新PERF_TEST表中的runningSample和agentState两个字段
	 * {@link PerfTestService#saveStatistics(net.grinder.SingleConsole, java.lang.Long)}
	 *
	 * @param id            id of {@link PerfTest}
	 * @param runningSample runningSample json string
	 * @param agentState    agentState json string
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateRuntimeStatistics(Long id, String runningSample, String agentState) {
		perfTestRepository.updateRuntimeStatistics(id, runningSample, agentState);
		perfTestRepository.flush();
	}

	/**
	 * 从控制台获取runningSample和agentState两个信息，并存到数据库中。
	 * To save statistics data when test is running and put into cache after that. If the console is not available, it returns null.
	 * {@link PerfTestSamplingCollectorListener#PerfTestSamplingCollectorListener(net.grinder.SingleConsole, java.lang.Long, org.ngrinder.perftest.service.PerfTestService, org.ngrinder.infra.schedule.ScheduledTaskService)}
	 *
	 * @param singleConsole single console.
	 * @param perfTestId    perfTest Id
	 */
	@Transactional(rollbackFor = Exception.class)
	public void saveStatistics(SingleConsole singleConsole, Long perfTestId) {
		String runningSample = getProperSizeRunningSample(singleConsole);
		String agentState = getProperSizedStatusString(singleConsole);
		updateRuntimeStatistics(perfTestId, runningSample, agentState);
	}

	/**
	 * 更新PERF_TEST表中type字段
	 * {@link PerfTestController#updateType(org.ngrinder.model.User, java.lang.String, java.lang.String)}
	 *
	 * @param perfTest perftest to mark
	 * @param type     type
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateType(PerfTest perfTest, Type type) {
		perfTestRepository.updateType(perfTest.getId(), type);
		perfTestRepository.flush();
	}

	/**
	 * 设置testErrorCause、status、lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest {@link PerfTest}
	 * @param reason   stop reason
	 * @return perftest with updated data
	 */
	@Transactional
	public PerfTest markAbnormalTermination(PerfTest perfTest, StopReason reason) {
		return markAbnormalTermination(perfTest, reason.getDisplay());
	}

	/**
	 * 设置testErrorCause、status、lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest {@link PerfTest}
	 * @param reason   stop reason
	 * @return perftest with updated data
	 */
	@Transactional
	public PerfTest markAbnormalTermination(PerfTest perfTest, String reason) {
		// Leave last status as test error cause
		perfTest.setTestErrorCause(perfTest.getStatus());
		return markStatusAndProgress(perfTest, Status.ABNORMAL_TESTING, reason);
	}

	/**
	 * 设置status、lastProgressMessage的值，并保存入库
	 * @param perfTest	{@link PerfTest} instance which will be saved.
	 * @param status	Status to be assigned
	 * @param message	progress message
	 * @return
	 */
	@Transactional
	@Override
	public PerfTest markStatusAndProgress(PerfTest perfTest, Status status, String message) {
		perfTest.setStatus(checkNotNull(status, "status should not be null"));
		return markProgress(perfTest, message);
	}

	/**
	 * 设置lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest perf test
	 * @param message  message to be recorded.
	 * @return saved {@link PerfTest}
	 */
	@Transactional
	public PerfTest markProgress(PerfTest perfTest, String message) {
		checkNotNull(perfTest);
		checkNotNull(perfTest.getId(), "perfTest should save Id");
		perfTest.setLastProgressMessage(message);
		LOGGER.debug("Progress : Test - {} : {}", perfTest.getId(), message);
		return perfTestRepository.saveAndFlush(perfTest);
	}

	/**
	 * 设置finishTime、TPS、Mean_Test_Time、Peak_TPS、Tests、Errors、status、lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest perf test
	 * @param status   status to be recorded.
	 * @param message  message to be recorded.
	 * @return perftest with latest status and data
	 */
	@Transactional
	public PerfTest markStatusAndProgressAndFinishTimeAndStatistics(PerfTest perfTest, Status status, String message) {
		// 设置finishTime
		perfTest.setFinishTime(new Date());
		// 设置TPS、Mean_Test_Time、Peak_TPS、Tests、Errors
		updatePerfTestAfterTestFinish(perfTest);
		// 设置status、lastProgressMessage
		return markStatusAndProgress(perfTest, status, message);
	}

	/**
	 * Update the given {@link PerfTest} properties after test finished.
	 *
	 * @param perfTest perfTest
	 */
	public void updatePerfTestAfterTestFinish(PerfTest perfTest) {
		checkNotNull(perfTest);
		Map<String, Object> result = consoleManager.getConsoleUsingPort(perfTest.getPort()).getStatisticsData();
		@SuppressWarnings("unchecked")
		Map<String, Object> totalStatistics = MapUtils.getMap(result, "totalStatistics", MapUtils.EMPTY_MAP);
		LOGGER.info("Total Statistics for test {}  is {}", perfTest.getId(), totalStatistics);
		perfTest.setTps(parseDoubleWithSafety(totalStatistics, "TPS", 0D));
		perfTest.setMeanTestTime(parseDoubleWithSafety(totalStatistics, "Mean_Test_Time_(ms)", 0D));
		perfTest.setPeakTps(parseDoubleWithSafety(totalStatistics, "Peak_TPS", 0D));
		perfTest.setTests(MapUtils.getDouble(totalStatistics, "Tests", 0D).longValue());
		perfTest.setErrors(MapUtils.getDouble(totalStatistics, "Errors", 0D).longValue());

	}

	/**
	 * 获取安全的Double值
	 * @param map
	 * @param key
	 * @param defaultValue
	 * @return
	 */
	double parseDoubleWithSafety(Map<?, ?> map, Object key, Double defaultValue) {
		Double doubleValue = MapUtils.getDouble(map, key, defaultValue);
		// Math.round 四舍五入取整
		return Math.round(doubleValue * 100D) / 100D;
	}

	/**
	 * 设置port、status、lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest    perftest to mark
	 * @param consolePort port of the console, on which the test is running
	 * @return saved perftest
	 */
	@Transactional
	public PerfTest markPerfTestConsoleStart(PerfTest perfTest, int consolePort) {
		return markStatusAndProgressAndPort(perfTest, consolePort);
	}

	/**
	 * 设置port、status、lastProgressMessage的值，并保存入库
	 *
	 * @param perfTest    perftest to mark
	 * @param consolePort port of the console, on which the test is running
	 * @return saved perftest
	 */
	@Transactional
	public PerfTest markStatusAndProgressAndPort(PerfTest perfTest, int consolePort) {
		perfTest.setPort(consolePort);
		String message = "Console is started on port " + consolePort;
		return markStatusAndProgress(perfTest, Status.START_CONSOLE_FINISHED, message);
	}

	/**
	 * 获取分发目录，一般是{NGRINDER_HOME}/perftest/{test_id}/dist
	 * {@link PerfTestRunnable#distributeFileOn(org.ngrinder.model.PerfTest, net.grinder.SingleConsole)}
	 * {@link PerfTestService#getCustomClassPath(org.ngrinder.model.PerfTest)}
	 * {@link PerfTestService#createConsoleProperties(org.ngrinder.model.PerfTest)}
	 * {@link PerfTestService#cleanUpDistFolder(org.ngrinder.model.PerfTest)}
	 * {@link PerfTestService#prepareDistribution(org.ngrinder.model.PerfTest)}
	 * @param perfTest    pefTest from which distribution directory calculated
	 * @return
	 */
	@Override
	public File getDistributionPath(PerfTest perfTest) {
		return config.getHome().getPerfTestDistDirectory(perfTest);
	}

	/**
	 * 获取进程线程策略文件，一般是{NGRINDER_HOME}/process_and_thread_policy.js
	 * {@link PerfTestController#getOne(org.ngrinder.model.User, java.lang.Long, org.springframework.ui.ModelMap)}
	 * {@link org.ngrinder.perftest.controller.PerfTestController#getQuickStart(org.ngrinder.model.User, java.lang.String, java.lang.String, org.springframework.ui.ModelMap)}
	 * {@link PerfTestService#calcProcessCountAndThreadCount(int)}
	 *
	 * @return policy javascript
	 */
	public String getProcessAndThreadPolicyScript() {
		return config.getProcessAndThreadPolicyScript();
	}

	/**
	 * 创建grinder.properties配置
	 * {@link PerfTestRunnable#doTest(org.ngrinder.model.PerfTest)}
	 * {@link PerfTestService#getGrinderProperties(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest      base data
	 * @param scriptHandler scriptHandler 只在设置grinder.properties中的grinder.script时用到
	 * @return created {@link GrinderProperties} instance
	 */
	public GrinderProperties getGrinderProperties(PerfTest perfTest, ScriptHandler scriptHandler) {
		try {
			// 首先使用默认配置，一般是{NGRINDER_HOME}/grinder.properties
			GrinderProperties grinderProperties = new GrinderProperties(config.getHome().getDefaultGrinderProperties());

			User user = perfTest.getCreatedUser();

			// Get all files in the script path
			String scriptName = perfTest.getScriptName();
			String grinderPropertiesFile = FilenameUtils.concat(FilenameUtils.getPath(scriptName), DEFAULT_GRINDER_PROPERTIES);
			// 从SVN获取最新的grinder.properties配置
			FileEntry userDefinedGrinderProperties = fileEntryService.getOne(user, grinderPropertiesFile, -1L);
			if (!config.isSecurityEnabled() && userDefinedGrinderProperties != null) {
				// Make the property overridden by user property.
				GrinderProperties userProperties = new GrinderProperties();
				userProperties.load(new StringReader(userDefinedGrinderProperties.getContent()));
				grinderProperties.putAll(userProperties);
			}
			// 根据测试配置设置对应的属性
			grinderProperties.setAssociatedFile(new File(DEFAULT_GRINDER_PROPERTIES));
			// 入参scriptHandler只在这里有用到
			grinderProperties.setProperty(GRINDER_PROP_SCRIPT, scriptHandler.getScriptExecutePath(scriptName));

			grinderProperties.setProperty(GRINDER_PROP_TEST_ID, "test_" + perfTest.getId());
			grinderProperties.setInt(GRINDER_PROP_AGENTS, getSafe(perfTest.getAgentCount()));
			grinderProperties.setInt(GRINDER_PROP_PROCESSES, getSafe(perfTest.getProcesses()));
			grinderProperties.setInt(GRINDER_PROP_THREAD, getSafe(perfTest.getThreads()));
			if (perfTest.isThresholdDuration()) {
				grinderProperties.setLong(GRINDER_PROP_DURATION, getSafe(perfTest.getDuration()));
				grinderProperties.setInt(GRINDER_PROP_RUNS, 0);
			} else {
				grinderProperties.setInt(GRINDER_PROP_RUNS, getSafe(perfTest.getRunCount()));
				if (grinderProperties.containsKey(GRINDER_PROP_DURATION)) {
					grinderProperties.remove(GRINDER_PROP_DURATION);
				}
			}
			grinderProperties.setProperty(GRINDER_PROP_ETC_HOSTS,
					StringUtils.defaultIfBlank(perfTest.getTargetHosts(), ""));
			grinderProperties.setBoolean(GRINDER_PROP_USE_CONSOLE, true);
			if (BooleanUtils.isTrue(perfTest.getUseRampUp())) {
				grinderProperties.setBoolean(GRINDER_PROP_THREAD_RAMPUP, perfTest.getRampUpType() == RampUp.THREAD);
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT, getSafe(perfTest.getRampUpStep()));
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT_INTERVAL,
						getSafe(perfTest.getRampUpIncrementInterval()));
				if (perfTest.getRampUpType() == RampUp.PROCESS) {
					grinderProperties.setInt(GRINDER_PROP_INITIAL_SLEEP_TIME, getSafe(perfTest.getRampUpInitSleepTime()));
				} else {
					grinderProperties.setInt(GRINDER_PROP_INITIAL_THREAD_SLEEP_TIME,
							getSafe(perfTest.getRampUpInitSleepTime()));
				}
				grinderProperties.setInt(GRINDER_PROP_INITIAL_PROCESS, getSafe(perfTest.getRampUpInitCount()));
			} else {
				grinderProperties.setInt(GRINDER_PROP_PROCESS_INCREMENT, 0);
			}
			grinderProperties.setInt(GRINDER_PROP_REPORT_TO_CONSOLE, 500);
			grinderProperties.setProperty(GRINDER_PROP_USER, perfTest.getCreatedUser().getUserId());
			grinderProperties.setProperty(GRINDER_PROP_JVM_CLASSPATH, getCustomClassPath(perfTest));
			grinderProperties.setInt(GRINDER_PROP_IGNORE_SAMPLE_COUNT, getSafe(perfTest.getIgnoreSampleCount()));
			grinderProperties.setBoolean(GRINDER_PROP_SECURITY, config.isSecurityEnabled());
			// For backward agent compatibility.
			// If the security is not enabled, pass it as jvm argument.
			// If enabled, pass it to grinder.param. In this case, I drop the
			// compatibility.
			if (StringUtils.isNotBlank(perfTest.getParam())) {
				String param = perfTest.getParam().replace("'", "\\'").replace(" ", "");
				if (config.isSecurityEnabled()) {
					grinderProperties.setProperty(GRINDER_PROP_PARAM, StringUtils.trimToEmpty(param));
				} else {
					String property = grinderProperties.getProperty(GRINDER_PROP_JVM_ARGUMENTS, "");
					property = property + " -Dparam=" + param + " ";
					grinderProperties.setProperty(GRINDER_PROP_JVM_ARGUMENTS, property);
				}
			}
			LOGGER.info("Grinder Properties : {} ", grinderProperties);
			return grinderProperties;
		} catch (Exception e) {
			throw processException("error while prepare grinder property for " + perfTest.getTestName(), e);
		}
	}

	/**
	 * 创建grinder.properties配置
	 *
	 * @param perfTest base data
	 * @return created {@link GrinderProperties} instance
	 */
	public GrinderProperties getGrinderProperties(PerfTest perfTest) {
		return getGrinderProperties(perfTest, new NullScriptHandler());
	}

	/**
	 * 构建Java的CLASSPATH，例如：.:lib:lib/xh-app-0.0.1-SNAPSHOT.jar:lib/gson-2.8.0.jar
	 * {@link PerfTestService#getGrinderProperties(org.ngrinder.model.PerfTest, org.ngrinder.script.handler.ScriptHandler)}
	 *
	 * @param perfTest perftest
	 * @return custom class path.
	 */

	@SuppressWarnings("ResultOfMethodCallIgnored")
	public String getCustomClassPath(PerfTest perfTest) {
		// {NGRINDER_HOME}/perftest/{test_id}/dist
		File perfTestDirectory = getDistributionPath(perfTest);
		// {NGRINDER_HOME}/perftest/{test_id}/dist/lib
		File libFolder = new File(perfTestDirectory, "lib");
		final StringBuffer customClassPath = new StringBuffer();
		customClassPath.append(".");
		if (libFolder.exists()) {
			customClassPath.append(File.pathSeparator).append("lib");
			libFolder.list(new FilenameFilter() {
				@Override
				public boolean accept(File dir, String name) {
					if (name.endsWith(".jar")) {
						customClassPath.append(File.pathSeparator).append("lib/").append(name);
					}
					return true;
				}
			});
		}
		return customClassPath.toString();
	}

	/**
	 * 获取测试主目录，一般是{NGRINDER_HOME}/perftest/x_xxx/{test_id}
	 *
	 * @param perfTest perfTest
	 * @return directory
	 */
	@Override
	public File getPerfTestDirectory(PerfTest perfTest) {
		return config.getHome().getPerfTestDirectory(perfTest);
	}

	/**
	 * 获取测试日志目录，一般是{NGRINDER_HOME}/perftest/x_xxx/{test_id}/logs
	 * {@link PerfTestService#getLogFile(long, java.lang.String)}
	 * {@link PerfTestService#getLogFiles(long)}
	 *
	 * @param testId test id
	 * @return logDir log file path of the test
	 */

	public File getLogFileDirectory(String testId) {
		return config.getHome().getPerfTestLogDirectory(testId);
	}

	/**
	 * 根据perfTest获取测试报告目录，一般是{NGRINDER_HOME}/perftest/{test_id}/report
	 * {@link PerfTestRunnable#doTest(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perftest
	 * @return reportDir report file path
	 */
	public File getReportFileDirectory(PerfTest perfTest) {
		return config.getHome().getPerfTestReportDirectory(perfTest);
	}

	/**
	 * 根据testId获取测试报告目录，一般是{NGRINDER_HOME}/perftest/{test_id}/report
	 * {@link PerfTestService#getAvailableReportPlugins(java.lang.Long)}
	 * {@link PerfTestService#getReportPluginDataFile(java.lang.Long, java.lang.String, java.lang.String)}
	 *
	 * @param testId testId
	 * @return reportDir report file path
	 */
	private File getReportFileDirectory(long testId) {
		return config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
	}

	/**
	 * 获取日志文件目录，{NGRINDER_HOME}/perftest/x_xxx/{test_id}/logs
	 * {@link PerfTestService#prepareDistribution(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perfTest
	 * @return logDir log file path of the test
	 */
	private File getLogFileDirectory(PerfTest perfTest) {
		return config.getHome().getPerfTestLogDirectory(perfTest);
	}

	/**
	 * 获取指定fileName的测试日志文件，一般是{NGRINDER_HOME}/perftest/x_xxx/{test_id}/logs/{fileName}
	 * {@link PerfTestController#downloadLog(org.ngrinder.model.User, long, java.lang.String, javax.servlet.http.HttpServletResponse)}
	 * {@link PerfTestController#showLog(org.ngrinder.model.User, long, java.lang.String, javax.servlet.http.HttpServletResponse)}
	 *
	 * @param testId   test id
	 * @param fileName file name of one logs of the test
	 * @return file report file path
	 */
	public File getLogFile(long testId, String fileName) {
		return new File(getLogFileDirectory(String.valueOf(testId)), fileName);
	}

	/**
	 * 获取测试目录下的所有日志文件，一般是{NGRINDER_HOME}/perftest/x_xxx/{test_id}/logs/*
	 * {@link PerfTestController#getReportSection(org.ngrinder.model.User, org.springframework.ui.ModelMap, long, int)}
	 * {@link PerfTestController#getLogs(org.ngrinder.model.User, java.lang.Long)}
	 *
	 * @param testId testId
	 * @return logFilesList log file list of that test
	 */
	public List<String> getLogFiles(long testId) {
		File logFileDirectory = getLogFileDirectory(String.valueOf(testId));
		// 路径不存在或者非目录，返回空
		if (!logFileDirectory.exists() || !logFileDirectory.isDirectory() || logFileDirectory.list() == null) {
			return Collections.emptyList();
		}
		return Arrays.asList(logFileDirectory.list());
	}

	/**
	 * 获取统计目录，{NGRINDER_HOME}/perftest/x_xxx/{test_id}/stat
	 *
	 * @param perfTest perftest
	 * @return
	 */
	@Override
	public File getStatisticPath(PerfTest perfTest) {
		return config.getHome().getPerfTestStatisticPath(perfTest);
	}

	/**
	 * Get report file(csv data) for give test .
	 * {@link PerfTestController#downloadCSV(org.ngrinder.model.User, long, javax.servlet.http.HttpServletResponse)}
	 *
	 * @param perfTest test
	 * @return reportFile data report file
	 */
	public File getCsvReportFile(PerfTest perfTest) {
		return config.getHome().getPerfTestCsvFile(perfTest);
	}

	/**
	 * get the data point interval of report data. Use dataPointCount / imgWidth as the interval. if interval is 1, it
	 * means we will get all point from report. If interval is 2, it means we will get 1 point from every 2 data.
	 * {@link PerfTestController#getPerfGraphData(java.lang.Long, java.lang.String[], boolean, int)}
	 * {@link PerfTestController#getReportSection(org.ngrinder.model.User, org.springframework.ui.ModelMap, long, int)}
	 *
	 * @param testId   test id
	 * @param dataType data type
	 * @param imgWidth image width
	 * @return interval interval value
	 */
	public int getReportDataInterval(long testId, String dataType, int imgWidth) {
		int pointCount = Math.max(imgWidth, MAX_POINT_COUNT);
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		int interval = 0;
		File targetFile = new File(reportFolder, dataType + DATA_FILE_EXTENSION);
		if (!targetFile.exists()) {
			LOGGER.warn("Report {} for test {} does not exist.", dataType, testId);
			return 0;
		}
		LineNumberReader lnr = null;

		FileInputStream in = null;
		InputStreamReader isr = null;
		try {
			in = new FileInputStream(targetFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(targetFile.length());
			int lineNumber = lnr.getLineNumber() + 1;
			interval = Math.max(lineNumber / pointCount, 1);
		} catch (Exception e) {
			LOGGER.error("Failed to get report data for {}", dataType, e);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}

		return interval;
	}

	/**
	 * get current running test status, which is, how many user run how many tests with some agents.
	 * {@link PerfTestController#getStatuses(org.ngrinder.model.User, java.lang.String)}
	 *
	 * @return PerfTestStatisticsList PerfTestStatistics list
	 */
	@Cacheable("current_perftest_statistics")
	@Transactional
	public Collection<PerfTestStatistics> getCurrentPerfTestStatistics() {
		Map<User, PerfTestStatistics> perfTestPerUser = newHashMap();
		for (PerfTest each : getAll(null, getProcessingOrTestingTestStatus())) {
			User lastModifiedUser = each.getCreatedUser().getUserBaseInfo();
			PerfTestStatistics perfTestStatistics = perfTestPerUser.get(lastModifiedUser);
			if (perfTestStatistics == null) {
				perfTestStatistics = new PerfTestStatistics(lastModifiedUser);
				perfTestPerUser.put(lastModifiedUser, perfTestStatistics);
			}
			perfTestStatistics.addPerfTest(each);
		}
		return perfTestPerUser.values();
	}

	private Gson gson = new GsonBuilder().excludeFieldsWithoutExposeAnnotation().create();

	/**
	 * Get agent info from saved file.
	 * {@link PerfTestController#refreshTestRunning(org.ngrinder.model.User, long)}
	 *
	 * @param perfTest perftest
	 * @return agent info map
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Map<String, HashMap> getAgentStat(PerfTest perfTest) {
		return gson.fromJson(perfTest.getAgentState(), HashMap.class);
	}

	/**
	 * Create {@link ConsoleProperties} based on given {@link PerfTest} instance.
	 * {@link PerfTestRunnable#startConsole(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perfTest
	 * @return {@link ConsoleProperties}
	 */
	public ConsoleProperties createConsoleProperties(PerfTest perfTest) {
		ConsoleProperties consoleProperties = ConsolePropertiesFactory.createEmptyConsoleProperties();
		try {
			consoleProperties.setAndSaveDistributionDirectory(new Directory(getDistributionPath(perfTest)));
			consoleProperties.setConsoleHost(config.getCurrentIP());
			consoleProperties.setIgnoreSampleCount(getSafe(perfTest.getIgnoreSampleCount()));
			consoleProperties.setSampleInterval(1000 * getSafe(perfTest.getSamplingInterval()));
		} catch (Exception e) {
			throw processException("Error while setting console properties", e);
		}
		return consoleProperties;
	}

	/**
	 * Check if the given perfTest has too many errors. (20%)
	 * {@link PerfTestRunnable#doNormalFinish(org.ngrinder.model.PerfTest, net.grinder.SingleConsole)}
	 *
	 * @param perfTest perftest
	 * @return true if too many errors.
	 */
	@SuppressWarnings("unchecked")
	public boolean hasTooManyError(PerfTest perfTest) {
		Map<String, Object> result = getStatistics(perfTest);
		Map<String, Object> totalStatistics = MapUtils.getMap(result, "totalStatistics", MapUtils.EMPTY_MAP);
		long tests = MapUtils.getDouble(totalStatistics, "Tests", 0D).longValue();
		long errors = MapUtils.getDouble(totalStatistics, "Errors", 0D).longValue();
		return ((((double) errors) / (tests + errors)) > 0.3d);
	}

	/**
	 * get test running statistic data from cache. If there is no cache data, will return empty statistic data.
	 * {@link PerfTestController#refreshTestRunning(org.ngrinder.model.User, long)}
	 * {@link PerfTestService#hasTooManyError(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perfTest
	 * @return test running statistic data
	 */
	@SuppressWarnings("unchecked")
	public Map<String, Object> getStatistics(PerfTest perfTest) {
		return gson.fromJson(perfTest.getRunningSample(), HashMap.class);
	}

	/**
	 * 获取所有的停止请求的List<PerfTest>
	 * {@link PerfTestRunnable#doFinish()}
	 *
	 */
	@Override
	public List<PerfTest> getAllStopRequested() {
		final List<PerfTest> perfTests = getAll(null, config.getRegion(), getProcessingOrTestingTestStatus());
		CollectionUtils.filter(perfTests, new Predicate() {
			@Override
			public boolean evaluate(Object object) {
				Boolean stopRequest = ((PerfTest) object).getStopRequest();
				if(stopRequest != null && stopRequest.equals(Boolean.TRUE)){
					return true;
				}
				return false;
			}
		});
		return perfTests;
	}

	/**
	 * 增加comment、tagString、tags
	 * {@link PerfTestController#leaveComment(org.ngrinder.model.User, java.lang.Long, java.lang.String, java.lang.String)}
	 *
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void addCommentOn(User user, Long testId, String testComment, String tagString) {
		PerfTest perfTest = getOne(user, testId);
		perfTest.setTestComment(testComment);
		attachTags(user, perfTest, tagString);
		perfTestRepository.save(perfTest);
	}

	/**
	 * Delete the distribution folder for the give perf test.
	 * {@link PerfTestRunnable#cleanUp(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perf test
	 */
	public void cleanUpDistFolder(PerfTest perfTest) {
		FileUtils.deleteQuietly(getDistributionPath(perfTest));
	}

	/**
	 * Clean up the data which is used in runtime only.
	 * {@link PerfTestRunnable#cleanUp(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perfTest
	 */
	public void cleanUpRuntimeOnlyData(PerfTest perfTest) {
		perfTest.setRunningSample("");
		perfTest.setAgentState("");
		perfTest.setMonitorState("");
		save(perfTest);
	}

	/**
	 * Put the given {@link org.ngrinder.monitor.share.domain.SystemInfo} maps into the given perftest entity.
	 * {@link MonitorCollectorPlugin#run()}
	 *
	 * @param perfTestId  id of perf test
	 * @param systemInfos systemDataModel map
	 */
	@Transactional(rollbackFor = Exception.class)
	public void updateMonitorStat(Long perfTestId, Map<String, SystemDataModel> systemInfos) {
		String json = gson.toJson(systemInfos);
		if (json.length() >= 2000) {
			Map<String, SystemDataModel> systemInfo = Maps.newHashMap();
			int i = 0;
			for (Entry<String, SystemDataModel> each : systemInfos.entrySet()) {
				if (i++ > 3) {
					break;
				}
				systemInfo.put(each.getKey(), each.getValue());
			}
			json = gson.toJson(systemInfo);
		}
		perfTestRepository.updatetMonitorStatus(perfTestId, json);
	}

	/**
	 * Get monitor status map for the given perfTest.
	 * {@link PerfTestController#refreshTestRunning(org.ngrinder.model.User, long)}
	 *
	 * @param perfTest perf test
	 * @return map of monitor name and monitor status.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Map<String, HashMap> getMonitorStat(PerfTest perfTest) {
		return gson.fromJson(perfTest.getMonitorState(), HashMap.class);
	}

	/**
	 * Get the monitor data interval value. In the normal, the image width is 700, and if the data count is too big,
	 * there will be too many points in the chart. So we will calculate the interval to get appropriate count of data to
	 * display. For example, interval value "2" means, get one record for every "2" records.
	 * {@link PerfTestController#getMonitorGraphData(long, java.lang.String, int)}
	 *
	 * @param testId     test id
	 * @param targetIP   ip address of monitor target
	 * @param imageWidth image with of the chart.
	 * @return interval value.
	 */
	public int getMonitorGraphInterval(long testId, String targetIP, int imageWidth) {
		File monitorDataFile = new File(config.getHome().getPerfTestReportDirectory(String.valueOf(testId)),
				MONITOR_FILE_PREFIX + targetIP + ".data");

		int pointCount = Math.max(imageWidth, MAX_POINT_COUNT);
		FileInputStream in = null;
		InputStreamReader isr = null;
		LineNumberReader lnr = null;
		int interval = 0;
		try {
			in = new FileInputStream(monitorDataFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(monitorDataFile.length());
			int lineNumber = lnr.getLineNumber() + 1;
			interval = Math.max(lineNumber / pointCount, 1);
		} catch (FileNotFoundException e) {
			LOGGER.info("Monitor data file does not exist at {}", monitorDataFile);
		} catch (IOException e) {
			LOGGER.info("Error while getting monitor:{} data file:{}", targetIP, monitorDataFile);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}
		return interval;
	}

	/**
	 * Get system monitor data and wrap the data as a string value like "[22,11,12,34,....]", which can be used directly
	 * in JS as a vector.
	 * {@link PerfTestController#getMonitorGraphData(long, java.lang.String, int)}
	 *
	 * @param testId       test id
	 * @param targetIP     ip address of the monitor target
	 * @param dataInterval interval value to get data. Interval value "2" means, get one record for every "2" records.
	 * @return return the data in map
	 */
	public Map<String, String> getMonitorGraph(long testId, String targetIP, int dataInterval) {
		Map<String, String> returnMap = Maps.newHashMap();
		File monitorDataFile = new File(config.getHome().getPerfTestReportDirectory(String.valueOf(testId)),
				MONITOR_FILE_PREFIX + targetIP + ".data");
		BufferedReader br = null;
		try {

			StringBuilder sbUsedMem = new StringBuilder("[");
			StringBuilder sbCPUUsed = new StringBuilder("[");
			StringBuilder sbNetReceived = new StringBuilder("[");
			StringBuilder sbNetSent = new StringBuilder("[");
			StringBuilder customData1 = new StringBuilder("[");
			StringBuilder customData2 = new StringBuilder("[");
			StringBuilder customData3 = new StringBuilder("[");
			StringBuilder customData4 = new StringBuilder("[");
			StringBuilder customData5 = new StringBuilder("[");

			br = new BufferedReader(new FileReader(monitorDataFile));
			br.readLine(); // skip the header.
			// "ip,system,collectTime,freeMemory,totalMemory,cpuUsedPercentage,receivedPerSec,sentPerSec"
			String line = br.readLine();
			int skipCount = dataInterval;
			// to be compatible with previous version, check the length before
			// adding
			while (StringUtils.isNotBlank(line)) {
				if (skipCount < dataInterval) {
					skipCount++;
				} else {
					skipCount = 1;
					String[] datalist = StringUtils.split(line, ",");
					if ("null".equals(datalist[4]) || "undefined".equals(datalist[4])) {
						sbUsedMem.append("null").append(",");
					} else {
						sbUsedMem.append(Long.valueOf(datalist[4]) - Long.valueOf(datalist[3])).append(",");
					}
					addCustomData(sbCPUUsed, 5, datalist);
					addCustomData(sbNetReceived, 6, datalist);
					addCustomData(sbNetSent, 7, datalist);
					addCustomData(customData1, 8, datalist);
					addCustomData(customData2, 9, datalist);
					addCustomData(customData3, 10, datalist);
					addCustomData(customData4, 11, datalist);
					addCustomData(customData5, 12, datalist);
					line = br.readLine();
				}
			}
			completeCustomData(returnMap, "cpu", sbCPUUsed);
			completeCustomData(returnMap, "memory", sbUsedMem);
			completeCustomData(returnMap, "received", sbNetReceived);
			completeCustomData(returnMap, "sent", sbNetSent);
			completeCustomData(returnMap, "customData1", customData1);
			completeCustomData(returnMap, "customData2", customData2);
			completeCustomData(returnMap, "customData3", customData3);
			completeCustomData(returnMap, "customData4", customData4);
			completeCustomData(returnMap, "customData5", customData5);
		} catch (IOException e) {
			LOGGER.info("Error while getting monitor {} data file at {}", targetIP, monitorDataFile);
		} finally {
			IOUtils.closeQuietly(br);
		}
		return returnMap;
	}

	/**
	 *
	 * {@link PerfTestController#getReport(org.springframework.ui.ModelMap, long)}
	 * @param customData
	 * @param index
	 * @param data
	 */
	private void addCustomData(StringBuilder customData, int index, String[] data) {
		if (data.length > index) {
			customData.append(data[index]).append(",");
		}
	}

	/**
	 *
	 * {@link PerfTestController#getReport(org.springframework.ui.ModelMap, long)}
	 * @param returnMap
	 * @param key
	 * @param customData
	 */
	private void completeCustomData(Map<String, String> returnMap, String key, StringBuilder customData) {
		if (customData.charAt(customData.length() - 1) == ',') {
			customData.deleteCharAt(customData.length() - 1);
		}
		returnMap.put(key, customData.append("]").toString());
	}

	/**
	 * 根据testId获取可用插件的报告
	 * 返回内容为List<Pair<String, String>>，第一个String是plugin名字（目录名），第二个String是kind名字（目录下的data文件名）
	 * {@link PerfTestController#getReport(org.springframework.ui.ModelMap, long)}
	 *
	 * @param testId test id
	 * @return plugin names
	 */
	public List<Pair<String, String>> getAvailableReportPlugins(Long testId) {
		List<Pair<String, String>> result = newArrayList();
		File reportDir = getReportFileDirectory(testId);
		if (reportDir.exists()) {
			for (File plugin : checkNotNull(reportDir.listFiles())) {
				// 判断report目录下是否存在子目录？也就是说只要有子目录，这个子目录名称就是插件名，里面的data文件就是kind文件
				if (plugin.isDirectory()) {
					for (String kind : checkNotNull(plugin.list())) {
						if (kind.endsWith(".data")) {
							result.add(Pair.of(plugin.getName(), FilenameUtils.getBaseName(kind)));
						}
					}
				}
			}
		}
		return result;
	}


	/**
	 * 获取插件报告图的间隔
	 * Get interval value of the monitor data of a plugin, like jvm monitor plugin.
	 * The usage of interval value is same as system monitor data.
	 * {@link PerfTestController#getReportPluginGraphData(long, java.lang.String, java.lang.String, int)}
	 *
	 * @param testId     test id
	 * @param plugin     plugin name
	 * @param kind       plugin kind
	 * @param imageWidth image with of the chart.
	 * @return interval value.
	 */
	public int getReportPluginGraphInterval(long testId, String plugin, String kind, int imageWidth) {
		return getRecordInterval(imageWidth, getReportPluginDataFile(testId, plugin, kind));
	}

	/**
	 * 根据图片宽度和数据文件，获取间隔，以保证绘图展示美观
	 * Get the interval value. In the normal, the image width is 700, and if the data count is too big,
	 * there will be too many points in the chart. So we will calculate the interval to get appropriate(适当的) count of data to
	 * display. For example, interval value "2" means, get one record for every "2" records.
	 * {@link PerfTestService#getReportPluginGraphInterval(long, java.lang.String, java.lang.String, int)}
	 *
	 * @param imageWidth
	 * @param dataFile
	 * @return
	 */
	private int getRecordInterval(int imageWidth, File dataFile) {
		// 折线图点数
		int pointCount = Math.max(imageWidth, MAX_POINT_COUNT);
		FileInputStream in = null;
		InputStreamReader isr = null;
		LineNumberReader lnr = null;
		int interval = 0;
		try {
			in = new FileInputStream(dataFile);
			isr = new InputStreamReader(in);
			lnr = new LineNumberReader(isr);
			lnr.skip(dataFile.length());
			// 计算间隔
			interval = Math.max((lnr.getLineNumber() + 1) / pointCount, 1);
		} catch (FileNotFoundException e) {
			LOGGER.error("data file not exist:{}", dataFile);
			LOGGER.error(e.getMessage(), e);
		} catch (IOException e) {
			LOGGER.error("Error while getting data file:{}", dataFile);
			LOGGER.error(e.getMessage(), e);
		} finally {
			IOUtils.closeQuietly(lnr);
			IOUtils.closeQuietly(isr);
			IOUtils.closeQuietly(in);
		}
		return interval;
	}

	/**
	 * 获取插件监控数据文件
	 * {@link PerfTestService#getReportPluginGraphInterval(long, java.lang.String, java.lang.String, int)}
	 * {@link PerfTestService#getReportPluginGraph(long, java.lang.String, java.lang.String, int)}
	 */
	private File getReportPluginDataFile(Long testId, String plugin, String kind) {
		// {NGRINDER_HOME}/perftest/{test_id}/report
		File reportDir = getReportFileDirectory(testId);
		// {NGRINDER_HOME}/perftest/{test_id}/report/{plugin}
		File pluginDir = new File(reportDir, plugin);
		// {NGRINDER_HOME}/perftest/{test_id}/report/{plugin}/{kind}.data
		return new File(pluginDir, kind + ".data");
	}

	/**
	 * Get plugin monitor data and wrap the data as a string value like "[22,11,12,34,....]", which can be used directly
	 * in JS as a vector.
	 *
	 * {@link PerfTestController#getReportPluginGraphData(long, java.lang.String, java.lang.String, int)}
	 *
	 * @param testId   test id
	 * @param plugin   plugin name
	 * @param kind     kind
	 * @param interval interval value to get data. Interval value "2" means, get one record for every "2" records.
	 * @return return the data in map
	 */
	public Map<String, Object> getReportPluginGraph(long testId, String plugin, String kind, int interval) {
		Map<String, Object> returnMap = Maps.newHashMap();
		// 获取文件{NGRINDER_HOME}/perftest/{test_id}/report/{plugin}/{kind}.data
		File pluginDataFile = getReportPluginDataFile(testId, plugin, kind);
		// 下面是读取文件
		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader(pluginDataFile));
			String header = br.readLine();

			StringBuilder headerSB = new StringBuilder("[");
			String[] headers = StringUtils.split(header, ",");
			String[] refinedHeaders = StringUtils.split(header, ",");
			List<StringBuilder> dataStringBuilders = new ArrayList<>(headers.length);

			for (int i = 0; i < headers.length; i++) {
				dataStringBuilders.add(new StringBuilder("["));
				String refinedHead = headers[i].trim().replaceAll(" ", "_");
				refinedHeaders[i] = refinedHead;
				headerSB.append("'").append(refinedHead).append("'").append(",");
			}
			String headerStringInJSONList = headerSB.deleteCharAt(headerSB.length() - 1).append("]").toString();
			returnMap.put("header", headerStringInJSONList);

			String line = br.readLine();
			int skipCount = interval;
			// to be compatible with previous version, check the length before adding
			while (StringUtils.isNotBlank(line)) {
				if (skipCount < interval) {
					skipCount++;
				} else {
					skipCount = 1;
					String[] records = StringUtils.split(line, ",");
					for (int i = 0; i < records.length; i++) {
						if ("null".equals(records[i]) || "undefined".equals(records[i])) {
							dataStringBuilders.get(i).append("null").append(",");
						} else {
							dataStringBuilders.get(i).append(records[i]).append(",");
						}
					}
					line = br.readLine();
				}
			}
			for (int i = 0; i < refinedHeaders.length; i++) {
				StringBuilder dataSB = dataStringBuilders.get(i);
				if (dataSB.charAt(dataSB.length() - 1) == ',') {
					dataSB.deleteCharAt(dataSB.length() - 1);
				}
				dataSB.append("]");
				returnMap.put(refinedHeaders[i], dataSB.toString());
			}
		} catch (IOException e) {
			LOGGER.error("Error while getting monitor: {} data file:{}", plugin, pluginDataFile);
			LOGGER.error(e.getMessage(), e);
		} finally {
			IOUtils.closeQuietly(br);
		}
		return returnMap;
	}

	/**
	 * 根据给定的report key获取一个报告文件，比如{NGRINDER_HOME}/perftest/{test_id}/report/TPS.data、{NGRINDER_HOME}/perftest/{test_id}/report/Errors.data
	 * {@link PerfTestService#getSingleReportDataAsJson(long, java.lang.String, int)}
	 * {@link PerfTestService#getReportData(long, java.lang.String, boolean, int)}
	 *
	 * @param testId test id
	 * @param key    key
	 * @return return file
	 */
	public File getReportDataFile(long testId, String key) {
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		return new File(reportFolder, key + ".data");
	}

	/**
	 * 根据给定的report key获取多个报告文件，比如{NGRINDER_HOME}/perftest/{test_id}/report/TPS-1.data、{NGRINDER_HOME}/perftest/{test_id}/report/Errors-2.data
	 * {@link PerfTestService#getReportData(long, java.lang.String, boolean, int)}
	 *
	 * @param testId test id
	 * @param key    report key
	 * @return return file list
	 */
	public List<File> getReportDataFiles(long testId, String key) {
		File reportFolder = config.getHome().getPerfTestReportDirectory(String.valueOf(testId));
		String wildcard = key + "*.data";
		FileFilter fileFilter = new WildcardFileFilter(wildcard);
		File[] files = reportFolder.listFiles(fileFilter);
		Arrays.sort(files, new Comparator<File>() {
			@Override
			public int compare(File o1, File o2) {
				return FilenameUtils.getBaseName(o1.getName()).compareTo(FilenameUtils.getBaseName(o2.getName()));
			}
		});
		return Arrays.asList(files);
	}

	/**
	 * 读取文件内容，返回字符串，格式为首尾中括号，中间每行内容以逗号隔开， "[" + 第一行 + "," + 第二行 + "," + ... + 最后一行 + "]"
	 * {@link PerfTestService#getSingleReportDataAsJson(long, java.lang.String, int)}
	 * {@link PerfTestService#getReportData(long, java.lang.String, boolean, int)}
	 *
	 * @param targetFile target file
	 * @param interval   interval to collect data
	 * @return json string
	 */
	public String getFileDataAsJson(File targetFile, int interval) {
		if (!targetFile.exists()) {
			return "[]";
		}
		StringBuilder reportData = new StringBuilder("[");
		FileReader reader = null;
		BufferedReader br = null;
		try {
			reader = new FileReader(targetFile);
			br = new BufferedReader(reader);
			String data = br.readLine();
			int current = 0;
			// TODO：这里判空，遇到空白行会中断
			while (StringUtils.isNotBlank(data)) {
				if (0 == current) {
					reportData.append(data);
					reportData.append(",");
				}
				// 控制每几行取一次数据，比如interval，那么只取1、4、7、...行的内容
				if (++current >= interval) {
					current = 0;
				}
				data = br.readLine();
			}
			// 倒数最后1个字符如果是逗号，则删除。charAt：获取指定索引处的字符
			if (reportData.charAt(reportData.length() - 1) == ',') {
				reportData.deleteCharAt(reportData.length() - 1);
			}
		} catch (IOException e) {
			LOGGER.error("Report data retrieval is failed: {}", e.getMessage());
			LOGGER.debug("Trace is : ", e);
		} finally {
			IOUtils.closeQuietly(reader);
			IOUtils.closeQuietly(br);
		}
		return reportData.append("]").toString();
	}

	/**
	 * 根据给定的report key获取一个报告文件，然后获取该文件的JSON字符串格式。（该方法是两个方法简单的组合）
	 * {@link PerfTestController#getReportSection(org.ngrinder.model.User, org.springframework.ui.ModelMap, long, int)}
	 *
	 * @param testId   test id
	 * @param key      key
	 * @param interval interval to collect data
	 * @return json list
	 */
	public String getSingleReportDataAsJson(long testId, String key, int interval) {
		File reportDataFile = getReportDataFile(testId, key);
		return getFileDataAsJson(reportDataFile, interval);
	}

	/**
	 * 根据testId和key获取报告，存在Pair中（第一个元素是报告名列表，第二个元素是报告内容列表）
	 * {@link PerfTestController#getPerfGraphData(java.lang.Long, java.lang.String[], boolean, int)}
	 *
	 * @param testId    test id
	 * @param key       report key
	 * @param onlyTotal 。
	 * @param interval  interval to collect data
	 * @return list containing label and tps value list
	 */
	public Pair<ArrayList<String>, ArrayList<String>> getReportData(long testId, String key, boolean onlyTotal, int interval) {
		Pair<ArrayList<String>, ArrayList<String>> resultPair = Pair.of(new ArrayList<String>(), new ArrayList<String>());
		// 设置为true，只取单个报告（也就是总的报告）；否则，取所有的报告（多个子报告文件）
		List<File> reportDataFiles = onlyTotal ? Lists.newArrayList(getReportDataFile(testId, key)) : getReportDataFiles(testId, key);
		for (File file : reportDataFiles) {
			String buildReportName = buildReportName(key, file);
			if (key.equals(buildReportName)) {
				buildReportName = "Total";
			} else {
				buildReportName = buildReportName.replace("_", " ");
			}
			// resultPair的第一个元素，类型是一个列表，每个元素都是String
			resultPair.getFirst().add(buildReportName);
			// resultPair的第二个元素，类型是一个列表，每个元素都是String（json格式的String），与第一个元素对应
			resultPair.getSecond().add(getFileDataAsJson(file, interval));
		}
		return resultPair;
	}

	/**
	 * 构建报告名称，文件存储在{NGRINDER_HOME}/perftest/{test_id}/report目录下，报告名TPS.data、Peak_TPS.data、Tests.data等
	 * {@link PerfTestService#getReportData(long, java.lang.String, boolean, int)}
	 *
	 * @param key
	 * @param file
	 * @return
	 */
	public String buildReportName(String key, File file) {
		// 移除后缀名：foo.txt-->foo；a/b/c.jpg-->a/b/c；a/b/c-->a/b/c；a.b/c-->a.b/c
		String reportName = FilenameUtils.removeExtension(file.getName());
		if (key.equals(reportName)) {
			return reportName;
		}
		int splitCount = 2;
		// 以中划线-为分割符分割字符串，至多分成2份。 比如：StringUtils.split("ab-cd-ef", "-", 2) = ["ab", "cd-ef"]
		String[] baseName = StringUtils.split(reportName, "-", splitCount);
		// 如果reportName可以分成2份，且第一份包含在INTERESTING_PER_TEST_STATISTICS中，则reportName等于第二份;
		if (baseName.length == splitCount) {
			if (SingleConsole.INTERESTING_PER_TEST_STATISTICS.contains(baseName[0])) {
				reportName = baseName[1];
			}
		}
		return reportName;
	}

	/**
	 * 准备分发的文件，文件存储在{NGRINDER_HOME}/perftest/{test_id}/dist目录下
	 * {@link PerfTestRunnable#doTest(org.ngrinder.model.PerfTest)}
	 *
	 * @param perfTest perfTest
	 * @return File location in which the perftest script and resources are distributed.
	 */
	public ScriptHandler prepareDistribution(PerfTest perfTest) {
		User user = perfTest.getCreatedUser();
		// 从SVN服务器上获取scriptEntry
		String scriptName = checkNotEmpty(perfTest.getScriptName(), "perfTest should have script name");
		Long scriptRevision = getSafe(perfTest.getScriptRevision());
		FileEntry tmpScriptEntry = fileEntryService.getOne(user, scriptName, scriptRevision);
		FileEntry scriptEntry = checkNotNull(tmpScriptEntry, "script should exist");
		// 获取分发目录，{NGRINDER_HOME}/perftest/x_xxx/{test_id}/dist，示例：/Users/ziling/.ngrinder/perftest/0_999/180/dist
		File perfTestDistDirectory = getDistributionPath(perfTest);
		//
		ProcessingResultPrintStream processingResult = new ProcessingResultPrintStream(new ByteArrayOutputStream());
		// 获取脚本目录下的所有文件
		ScriptHandler handler = scriptHandlerFactory.getHandler(scriptEntry);
		// 拷贝SVN上脚本相关文件（整个脚本工程）到{NGRINDER_HOME}/perftest/x_xxx/testId/dist目录
		handler.prepareDist(perfTest.getId(), user, scriptEntry, perfTestDistDirectory, config.getControllerProperties(), processingResult);
		LOGGER.info("File write is completed in {}", perfTestDistDirectory);
		// 分发文件不成功则记录日志文件
		if (!processingResult.isSuccess()) {
			File logDir = new File(getLogFileDirectory(perfTest), "distribution_log.txt");
			try {
				FileUtils.writeByteArrayToFile(logDir, processingResult.getLogByteArray());
			} catch (IOException e) {
				noOp();
			}
			throw processException("Error while file distribution is prepared.");
		}
		return handler;
	}

	/**
	 * 获取配置中允许的最大同时运行测试个数，key=controller.max_concurrent_test
	 * {@link PerfTestService#canExecuteTestMore()}
	 * {@link PerfTestRunnable#canExecuteMore( }
	 *
	 * @return maximum concurrent test
	 */
	int getMaximumConcurrentTestCount() {
		return config.getControllerProperties().getPropertyInt(PROP_CONTROLLER_MAX_CONCURRENT_TEST);
	}

	/**
	 * 是否能够执行更多测试
	 * Status为StatusCategory.PROGRESSING或StatusCategory.TESTING的个数 是否小于 系统配置controller.max_concurrent_test 的值
	 *
	 * @return true if possible
	 * @deprecated
	 */
	@SuppressWarnings("UnusedDeclaration")
	public boolean canExecuteTestMore() {
		return count(null, Status.getProcessingOrTestingTestStatus()) < getMaximumConcurrentTestCount();
	}

	/**
	 * 根据虚拟用户数计算最佳的进程数和线程数。调用js文件的方法来计算的。 虚拟用户数 = 进程数 * 线程数
	 * vuser = 1：进程数 = 1
	 * 2 <= vuser <= 80：进程数 = 2
	 * 80 < vuser < 400：进程数 = vuser/40 + 1，介于3~10之间
	 * vuser >= 400：进程数=10，至多10个进程
	 *
	 * @param newVuser the count of virtual users per agent
	 * @return optimal process thread count
	 */
	public ProcessCountAndThreadCount calcProcessCountAndThreadCount(int newVuser) {
		try {
			String script = getProcessAndThreadPolicyScript();
			ScriptEngine engine = new ScriptEngineManager().getEngineByName("javascript");
			engine.eval(script);
			int processCount = Double.valueOf(engine.eval("getProcessCount(" + newVuser + ")").toString()).intValue();
			int threadCount = Double.valueOf(engine.eval("getThreadCount(" + newVuser + ")").toString()).intValue();
			return new ProcessCountAndThreadCount(processCount, threadCount);
		} catch (ScriptException e) {
			LOGGER.error("Error occurs while calc process and thread", e);
		}
		return new ProcessCountAndThreadCount(1, 1);
	}

	/**
	 * 获取PerfTestRepository
	 * {@link org.ngrinder.perftest.service.ClusteredPerfTestService#getNextRunnablePerfTestCandidate()}
	 * @return
	 */
	public PerfTestRepository getPerfTestRepository() {
		return perfTestRepository;
	}

	/**
	 * 获取配置，Config是PerfTestService的一个成员变量，可以get、set
	 * {@link org.ngrinder.perftest.service.ClusteredPerfTestService#getNextRunnablePerfTestCandidate()}
	 * @return
	 */
	public Config getConfig() {
		return config;
	}

	/**
	 * 设置配置
	 *
	 * @param config
	 */
	public void setConfig(Config config) {
		this.config = config;
	}
}

