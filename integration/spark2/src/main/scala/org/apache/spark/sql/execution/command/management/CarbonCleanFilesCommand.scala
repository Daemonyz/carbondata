/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command.management

import org.apache.spark.sql.{CarbonEnv, Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.execution.command.{Checker, DataCommand}
import org.apache.spark.sql.optimizer.CarbonFilters

import org.apache.carbondata.api.CarbonStore
import org.apache.carbondata.common.logging.LogServiceFactory
import org.apache.carbondata.core.util.CarbonProperties
import org.apache.carbondata.events.{CleanFilesPostEvent, CleanFilesPreEvent, OperationContext, OperationListenerBus}
import org.apache.carbondata.spark.util.CommonUtil

/**
 * Clean data in table
 * If table name is specified and forceTableClean is false, it will clean garbage
 * segment (MARKED_FOR_DELETE state).
 * If table name is specified and forceTableClean is true, it will delete all data
 * in the table.
 * If table name is not provided, it will clean garbage segment in all tables.
 */
case class CarbonCleanFilesCommand(
    databaseNameOp: Option[String],
    tableName: Option[String],
    forceTableClean: Boolean = false)
  extends DataCommand {

  override def processData(sparkSession: SparkSession): Seq[Row] = {
    val carbonTable = CarbonEnv.getCarbonTable(databaseNameOp, tableName.get)(sparkSession)
    val operationContext = new OperationContext
    val cleanFilesPreEvent: CleanFilesPreEvent =
      CleanFilesPreEvent(carbonTable,
        sparkSession)
    OperationListenerBus.getInstance.fireEvent(cleanFilesPreEvent, operationContext)
    if (tableName.isDefined) {
      Checker.validateTableExists(databaseNameOp, tableName.get, sparkSession)
      if (forceTableClean) {
        deleteAllData(sparkSession, databaseNameOp, tableName.get)
      } else {
        cleanGarbageData(sparkSession, databaseNameOp, tableName.get)
      }
    } else {
      cleanGarbageDataInAllTables(sparkSession)
    }
    val cleanFilesPostEvent: CleanFilesPostEvent =
      CleanFilesPostEvent(carbonTable, sparkSession)
    OperationListenerBus.getInstance.fireEvent(cleanFilesPostEvent, operationContext)
    Seq.empty
  }

  private def deleteAllData(sparkSession: SparkSession,
      databaseNameOp: Option[String], tableName: String): Unit = {
    val dbName = CarbonEnv.getDatabaseName(databaseNameOp)(sparkSession)
    val databaseLocation = CarbonEnv.getDatabaseLocation(dbName, sparkSession)
    CarbonStore.cleanFiles(
      dbName,
      tableName,
      databaseLocation,
      null,
      forceTableClean)
  }

  private def cleanGarbageData(sparkSession: SparkSession,
      databaseNameOp: Option[String], tableName: String): Unit = {
    val carbonTable = CarbonEnv.getCarbonTable(databaseNameOp, tableName)(sparkSession)
    val partitions: Option[Seq[String]] = if (carbonTable.isHivePartitionTable) {
      Some(CarbonFilters.getPartitions(
        Seq.empty[Expression],
        sparkSession,
        TableIdentifier(tableName, databaseNameOp)))
    } else {
      None
    }
    CarbonStore.cleanFiles(
      CarbonEnv.getDatabaseName(databaseNameOp)(sparkSession),
      tableName,
      CarbonProperties.getStorePath,
      carbonTable,
      forceTableClean,
      partitions)
  }

  // Clean garbage data in all tables in all databases
  private def cleanGarbageDataInAllTables(sparkSession: SparkSession): Unit = {
    try {
      val databases = sparkSession.sessionState.catalog.listDatabases()
      databases.foreach(dbName => {
        val databaseLocation = CarbonEnv.getDatabaseLocation(dbName, sparkSession)
        CommonUtil.cleanInProgressSegments(databaseLocation, dbName)
      })
    } catch {
      case e: Throwable =>
        // catch all exceptions to avoid failure
        LogServiceFactory.getLogService(this.getClass.getCanonicalName)
          .error(e, "Failed to clean in progress segments")
    }
  }
}
