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

import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

import org.junit.Test;
import org.ngrinder.AbstractNGrinderTransactionalTest;
import org.ngrinder.perftest.model.ProcessCountAndThreadCount;
import org.ngrinder.perftest.model.ProcessCountAndThreadCount;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link PerfTestService} test.
 *
 * @author JunHo Yoon
 * @since 3.0
 */
public class PerfTestProcessAndThreadPolicyServiceTest extends AbstractNGrinderTransactionalTest {

	@Autowired
	private PerfTestService perfTestService;

	@Test
	public void testVUser() {
		assertThat(perfTestService.getProcessAndThreadPolicyScript(), notNullValue());
		ProcessCountAndThreadCount processCountAndThreadCount = perfTestService.calcProcessCountAndThreadCount(100);
		assertThat(processCountAndThreadCount, notNullValue());
		System.out.println(processCountAndThreadCount);

	}
}
