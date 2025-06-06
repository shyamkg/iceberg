/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.aws.glue;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.Table;
import org.apache.iceberg.aws.AwsIntegTestUtil;
import org.apache.iceberg.aws.AwsProperties;
import org.apache.iceberg.aws.dynamodb.DynamoDbLockManager;
import org.apache.iceberg.aws.s3.S3FileIOProperties;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.relocated.com.google.common.util.concurrent.MoreExecutors;
import org.apache.iceberg.util.Tasks;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariables;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.DeleteTableRequest;

@EnabledIfEnvironmentVariables({
  @EnabledIfEnvironmentVariable(named = AwsIntegTestUtil.AWS_ACCESS_KEY_ID, matches = ".*"),
  @EnabledIfEnvironmentVariable(named = AwsIntegTestUtil.AWS_SECRET_ACCESS_KEY, matches = ".*"),
  @EnabledIfEnvironmentVariable(named = AwsIntegTestUtil.AWS_SESSION_TOKEN, matches = ".*"),
  @EnabledIfEnvironmentVariable(named = AwsIntegTestUtil.AWS_REGION, matches = ".*"),
  @EnabledIfEnvironmentVariable(named = AwsIntegTestUtil.AWS_TEST_BUCKET, matches = ".*")
})
public class TestGlueCatalogLock extends GlueTestBase {

  private static String lockTableName;
  private static DynamoDbClient dynamo;

  @BeforeAll
  public static void beforeClass() {
    GlueTestBase.beforeClass();
    String testBucketPath = "s3://" + TEST_BUCKET_NAME + "/" + TEST_PATH_PREFIX;
    lockTableName = getRandomName();
    glueCatalog = new GlueCatalog();
    AwsProperties awsProperties = new AwsProperties();
    S3FileIOProperties s3FileIOProperties = new S3FileIOProperties();
    dynamo = CLIENT_FACTORY.dynamo();
    glueCatalog.initialize(
        CATALOG_NAME,
        testBucketPath,
        awsProperties,
        s3FileIOProperties,
        GLUE,
        new DynamoDbLockManager(dynamo, lockTableName),
        ImmutableMap.of());
  }

  @AfterAll
  public static void afterClass() {
    GlueTestBase.afterClass();
    dynamo.deleteTable(DeleteTableRequest.builder().tableName(lockTableName).build());
  }

  @Test
  public void testParallelCommitMultiThreadSingleCommit() {
    int nThreads = 20;
    String namespace = createNamespace();
    String tableName = getRandomName();
    createTable(namespace, tableName);
    Table table = glueCatalog.loadTable(TableIdentifier.of(namespace, tableName));
    DataFile dataFile =
        DataFiles.builder(partitionSpec)
            .withPath("/path/to/data-a.parquet")
            .withFileSizeInBytes(1)
            .withRecordCount(1)
            .build();

    List<AppendFiles> pendingCommits =
        IntStream.range(0, nThreads)
            .mapToObj(i -> table.newAppend().appendFile(dataFile))
            .collect(Collectors.toList());

    ExecutorService executorService =
        MoreExecutors.getExitingExecutorService(
            (ThreadPoolExecutor) Executors.newFixedThreadPool(nThreads));

    Tasks.range(nThreads)
        .retry(10000)
        .throwFailureWhenFinished()
        .executeWith(executorService)
        .run(i -> pendingCommits.get(i).commit());

    table.refresh();
    assertThat(table.history()).as("Commits should all succeed sequentially").hasSize(nThreads);
    assertThat(table.currentSnapshot().allManifests(table.io())).hasSize(nThreads);
  }

  @Test
  public void testParallelCommitMultiThreadMultiCommit() {
    String namespace = createNamespace();
    String tableName = getRandomName();
    createTable(namespace, tableName);
    Table table = glueCatalog.loadTable(TableIdentifier.of(namespace, tableName));
    String fileName = UUID.randomUUID().toString();
    DataFile file =
        DataFiles.builder(table.spec())
            .withPath(FileFormat.PARQUET.addExtension(fileName))
            .withRecordCount(2)
            .withFileSizeInBytes(0)
            .build();

    ExecutorService executorService =
        MoreExecutors.getExitingExecutorService(
            (ThreadPoolExecutor) Executors.newFixedThreadPool(2));

    AtomicInteger barrier = new AtomicInteger(0);
    int threadsCount = 2;
    Tasks.range(threadsCount)
        .stopOnFailure()
        .throwFailureWhenFinished()
        .executeWith(executorService)
        .run(
            index -> {
              for (int numCommittedFiles = 0; numCommittedFiles < 10; numCommittedFiles++) {
                final int currentFilesCount = numCommittedFiles;
                Awaitility.await()
                    .pollInterval(Duration.ofMillis(10))
                    .atMost(Duration.ofSeconds(10))
                    .until(() -> barrier.get() >= currentFilesCount * threadsCount);
                table.newFastAppend().appendFile(file).commit();
                barrier.incrementAndGet();
              }
            });

    table.refresh();
    assertThat(table.history()).as("Commits should all succeed sequentially").hasSize(20);
    assertThat(table.currentSnapshot().allManifests(table.io())).hasSize(20);
  }
}
