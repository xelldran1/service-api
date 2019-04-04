/*
 * Copyright 2019 EPAM Systems
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.epam.ta.reportportal.core.analyzer.impl;

import com.epam.ta.reportportal.core.analyzer.LogIndexer;
import com.epam.ta.reportportal.core.analyzer.client.IndexerServiceClient;
import com.epam.ta.reportportal.core.analyzer.model.IndexLaunch;
import com.epam.ta.reportportal.core.analyzer.model.IndexTestItem;
import com.epam.ta.reportportal.dao.LaunchRepository;
import com.epam.ta.reportportal.dao.LogRepository;
import com.epam.ta.reportportal.dao.TestItemRepository;
import com.epam.ta.reportportal.entity.enums.LogLevel;
import com.epam.ta.reportportal.entity.enums.TestItemIssueGroup;
import com.epam.ta.reportportal.entity.item.TestItem;
import com.epam.ta.reportportal.entity.launch.Launch;
import com.epam.ta.reportportal.entity.log.Log;
import com.epam.ta.reportportal.exception.ReportPortalException;
import com.epam.ta.reportportal.ws.model.ErrorType;
import com.epam.ta.reportportal.ws.model.project.AnalyzerConfig;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.epam.ta.reportportal.util.Predicates.ITEM_CAN_BE_INDEXED;
import static com.epam.ta.reportportal.util.Predicates.LAUNCH_CAN_BE_INDEXED;
import static java.util.stream.Collectors.toList;

/**
 * @author <a href="mailto:ihar_kahadouski@epam.com">Ihar Kahadouski</a>
 */
@Service
public class LogIndexerService implements LogIndexer {
	private static Logger LOGGER = LoggerFactory.getLogger(LogIndexerService.class);

	private final TestItemRepository testItemRepository;

	private final LaunchRepository launchRepository;

	private final IndexerServiceClient indexerServiceClient;

	private final LogRepository logRepository;

	private final AnalyzerStatusCache analyzerStatusCache;

	@Autowired
	public LogIndexerService(TestItemRepository testItemRepository, LaunchRepository launchRepository,
			IndexerServiceClient indexerServiceClient, LogRepository logRepository, AnalyzerStatusCache analyzerStatusCache) {
		this.testItemRepository = testItemRepository;
		this.launchRepository = launchRepository;
		this.indexerServiceClient = indexerServiceClient;
		this.logRepository = logRepository;
		this.analyzerStatusCache = analyzerStatusCache;
	}

	@Override
	public CompletableFuture<Long> indexLogs(Long projectId, List<Long> launchIds, AnalyzerConfig analyzerConfig) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				analyzerStatusCache.indexingStarted(projectId);
				List<IndexLaunch> indexLaunches = prepareLaunches(launchIds, analyzerConfig);
				return indexerServiceClient.index(indexLaunches);
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
				throw new ReportPortalException(e.getMessage());
			} finally {
				analyzerStatusCache.indexingFinished(projectId);
			}
		});
	}

	@Override
	public CompletableFuture<Long> indexLogs(Long projectId, Long launchId, List<Long> itemIds, AnalyzerConfig analyzerConfig) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				analyzerStatusCache.indexingStarted(projectId);
				Launch launch = launchRepository.findById(launchId)
						.orElseThrow(() -> new ReportPortalException(ErrorType.LAUNCH_NOT_FOUND, launchId));
				if (LAUNCH_CAN_BE_INDEXED.test(launch)) {
					List<IndexTestItem> rqTestItems = prepareItemsForIndexing(testItemRepository.findAllById(itemIds));
					if (!CollectionUtils.isEmpty(rqTestItems)) {
						IndexLaunch indexLaunch = createIndexLaunch(projectId,
								launch.getId(),
								launch.getName(),
								analyzerConfig,
								rqTestItems
						);
						return indexerServiceClient.index(Lists.newArrayList(indexLaunch));
					}
				}
				return 0L;
			} catch (Exception e) {
				LOGGER.error(e.getMessage(), e);
				throw new ReportPortalException(e.getMessage());
			} finally {
				analyzerStatusCache.indexingFinished(projectId);
			}
		});
	}

	@Override
	public void deleteIndex(Long project) {
		indexerServiceClient.deleteIndex(project);
	}

	@Override
	public void cleanIndex(Long index, List<Long> ids) {
		CompletableFuture.runAsync(() -> indexerServiceClient.cleanIndex(index, ids));
	}

	/**
	 * Creates {@link IndexLaunch} for specified log if
	 * it is suitable for indexing or else returns <code>null</code>
	 *
	 * @param log Log to be converted
	 * @return Prepared {@link IndexLaunch} rq for indexing
	 */
	private IndexLaunch createRqLaunch(Log log) {
		if (!isLevelSuitable(log)) {
			return null;
		}
		IndexLaunch rqLaunch = null;
		TestItem testItem = testItemRepository.findById(log.getTestItem().getItemId())
				.orElseThrow(() -> new ReportPortalException(ErrorType.TEST_ITEM_NOT_FOUND, log.getTestItem().getItemId()));
		if (ITEM_CAN_BE_INDEXED.test(testItem)) {
			Launch launch = launchRepository.findById(testItem.getLaunch().getId())
					.orElseThrow(() -> new ReportPortalException(ErrorType.LAUNCH_NOT_FOUND, testItem.getLaunch().getId()));
			if (LAUNCH_CAN_BE_INDEXED.test(launch)) {
				rqLaunch = new IndexLaunch();
				rqLaunch.setLaunchId(launch.getId());
				rqLaunch.setLaunchName(launch.getName());
				rqLaunch.setProjectId(launch.getProjectId());
				rqLaunch.setTestItems(Collections.singletonList(AnalyzerUtils.fromTestItem(testItem, Collections.singletonList(log))));
			}
		}
		return rqLaunch;
	}

	/**
	 * Checks if the log is suitable for indexing in analyzer.
	 * Log's level is greater or equal than {@link LogLevel#ERROR}
	 *
	 * @param log Log for check
	 * @return true if suitable
	 */
	private boolean isLevelSuitable(Log log) {
		return null != log && null != log.getLogLevel() && log.getLogLevel() >= LogLevel.ERROR.toInt();
	}

	/**
	 * Prepare launches for indexing
	 *
	 * @param launchIds      - Launches id to be prepared
	 * @param analyzerConfig - Analyzer config
	 * @return List of prepared launches for indexing
	 */
	private List<IndexLaunch> prepareLaunches(List<Long> launchIds, AnalyzerConfig analyzerConfig) {
		return launchIds.stream().map(launchId -> {
			Launch launch = launchRepository.findById(launchId)
					.orElseThrow(() -> new ReportPortalException(ErrorType.LAUNCH_NOT_FOUND, launchId));
			if (LAUNCH_CAN_BE_INDEXED.test(launch)) {
				List<TestItem> testItems = testItemRepository.findAllNotInIssueByLaunch(launchId,
						TestItemIssueGroup.TO_INVESTIGATE.getLocator()
				);
				List<IndexTestItem> rqTestItems = prepareItemsForIndexing(testItems);
				if (!CollectionUtils.isEmpty(rqTestItems)) {
					return createIndexLaunch(launch.getProjectId(), launch.getId(), launch.getName(), analyzerConfig, rqTestItems);
				}
			}
			return null;
		}).filter(Objects::nonNull).collect(Collectors.toList());
	}

	private IndexLaunch createIndexLaunch(Long projectId, Long launchId, String name, AnalyzerConfig analyzerConfig,
			List<IndexTestItem> rqTestItems) {
		IndexLaunch rqLaunch = new IndexLaunch();
		rqLaunch.setLaunchId(launchId);
		rqLaunch.setLaunchName(name);
		rqLaunch.setProjectId(projectId);
		rqLaunch.setAnalyzerConfig(analyzerConfig);
		rqLaunch.setTestItems(rqTestItems);
		return rqLaunch;
	}

	/**
	 * Creates {@link IndexTestItem} from suitable {@link TestItem}
	 * for indexing with logs greater than {@link LogLevel#ERROR}
	 *
	 * @param testItems Test item for preparing
	 * @return Prepared list of {@link IndexTestItem} for indexing
	 */
	private List<IndexTestItem> prepareItemsForIndexing(List<TestItem> testItems) {
		return testItems.stream()
				.filter(ITEM_CAN_BE_INDEXED)
				.map(it -> AnalyzerUtils.fromTestItem(it,
						logRepository.findAllByTestItemItemIdInAndLogLevelIsGreaterThanEqual(Collections.singletonList(it.getItemId()),
								LogLevel.ERROR.toInt()
						)
				))
				.filter(it -> !CollectionUtils.isEmpty(it.getLogs()))
				.collect(toList());
	}
}
