/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package edp.wormhole.flinkx.swifts

import com.alibaba.fastjson.{JSON, JSONObject}
import edp.wormhole.flinkx.common.{ExceptionConfig, ExceptionProcess, WormholeFlinkxConfig}
import edp.wormhole.flinkx.pattern.JsonFieldName.{KEYBYFILEDS, OUTPUT}
import edp.wormhole.flinkx.pattern.{OutputType, PatternGenerator, PatternOutput, PatternOutputFilter}
import edp.wormhole.flinkx.util.FlinkSchemaUtils
import edp.wormhole.swifts.{ConnectionMemoryStorage, SqlOptType}
import edp.wormhole.util.swifts.SwiftsSql
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.cep.scala.CEP
import org.apache.flink.streaming.api.scala.{DataStream, _}
import org.apache.flink.table.api.Table
import org.apache.flink.table.api.scala.{StreamTableEnvironment, _}
import org.apache.flink.types.Row
import org.slf4j.{Logger, LoggerFactory}


class SwiftsProcess(dataStream: DataStream[Row],
                    exceptionConfig: ExceptionConfig,
                    tableEnv: StreamTableEnvironment,
                    swiftsSql: Option[Array[SwiftsSql]],
                    specialConfigObj: JSONObject,
                    timeCharacteristic: String,
                    config: WormholeFlinkxConfig) extends AbstractSwiftsProcess(tableEnv, specialConfigObj, timeCharacteristic) {

  private lazy val logger: Logger = LoggerFactory.getLogger(this.getClass)
  private var preSchemaMap: Map[String, (TypeInformation[_], Int)] = FlinkSchemaUtils.immutableSourceSchemaMap
  private val lookupTag = OutputTag[String]("lookupException")

  def process(): (DataStream[Row], Map[String, (TypeInformation[_], Int)]) = {
    var transformedStream = dataStream
    if (swiftsSql.nonEmpty) {
      val swiftsSqlGet = swiftsSql.get
      for (index <- swiftsSqlGet.indices) {
        val element = swiftsSqlGet(index)
        SqlOptType.withName(element.optType) match {
          case SqlOptType.FLINK_SQL => transformedStream = doFlinkSql(transformedStream, element.sql, index)
          case SqlOptType.CEP => transformedStream = doCEP(transformedStream, element.sql, index)
          case SqlOptType.JOIN | SqlOptType.LEFT_JOIN => transformedStream = doLookup(transformedStream, element, index)
        }
      }
    }
    (transformedStream, preSchemaMap)
  }

  private def doFlinkSql(transformedStream: DataStream[Row], sql: String, index: Int): DataStream[Row] = {
    var table: Table = getKeyByStream(transformedStream).toTable(tableEnv, buildExpression(preSchemaMap): _*)
    table.printSchema()
    val projectClause = sql.substring(0, sql.toLowerCase.indexOf(" from ")).trim
    val namespaceTable = exceptionConfig.sourceNamespace.split("\\.")(3)
    val newSql = sql.replace(s" $namespaceTable ", s" $table ")
    logger.info(newSql + "@@@@@@@@@@@@@the new sql")
    table = tableEnv.sqlQuery(newSql)
    table.printSchema()
    val value = FlinkSchemaUtils.getSchemaMapFromTable(table.getSchema, projectClause, FlinkSchemaUtils.udfSchemaMap.toMap, specialConfigObj)
    preSchemaMap = value
    covertTable2Stream(table, FlinkSchemaUtils.tableFieldTypeArray(table.getSchema, preSchemaMap))
  }

  private def getKeyByStream(transformedStream: DataStream[Row]): DataStream[Row] = {
    if (null != specialConfigObj && specialConfigObj.containsKey(FlinkxSwiftsConstants.KEY_BY_FIELDS)) {
      val streamKeyByFieldsIndex = specialConfigObj.getString(FlinkxSwiftsConstants.KEY_BY_FIELDS).split(",").map(preSchemaMap(_)._2)
      transformedStream.keyBy(streamKeyByFieldsIndex: _*)
    }
    else transformedStream
  }

  private def doCEP(transformedStream: DataStream[Row], sql: String, index: Int): DataStream[Row] = {
    var resultDataStream: DataStream[Row] = null
    val patternSeq = JSON.parseObject(sql)
    val patternGenerator = new PatternGenerator(patternSeq, preSchemaMap, exceptionConfig, config)
    val pattern = patternGenerator.getPattern
    val keyByFields = patternSeq.getString(KEYBYFILEDS.toString).trim
    val patternStream = if (keyByFields != null && keyByFields.nonEmpty) {
      val keyArray = keyByFields.split(",").map(key => preSchemaMap(key)._2)
      CEP.pattern(transformedStream.keyBy(keyArray: _*), pattern)
    } else CEP.pattern(transformedStream, pattern)
    val patternOutput = new PatternOutput(patternSeq.getJSONObject(OUTPUT.toString), preSchemaMap)
    val patternOutputStreamType: (Array[String], Array[TypeInformation[_]]) = patternOutput.getPatternOutputRowType(keyByFields)
    setSwiftsSchemaWithCEP(patternOutput, index, keyByFields)
    val patternOutputStream: DataStream[(Boolean, Row)] = patternOutput.getOutput(patternStream, patternGenerator, keyByFields)
    resultDataStream = filterException(patternOutputStream, patternOutputStreamType)
    println(resultDataStream.dataType.toString + "in  doCep")
    resultDataStream
  }

  private def setSwiftsSchemaWithCEP(patternOutput: PatternOutput, index: Int, keyByFields: String): Unit = {
    preSchemaMap = if (OutputType.outputType(patternOutput.getOutputType) == OutputType.AGG) {
      val (fieldNames, fieldTypes) = patternOutput.getPatternOutputRowType(keyByFields)
      FlinkSchemaUtils.getSchemaMapFromArray(fieldNames, fieldTypes)
    } else preSchemaMap
  }

  def filterException(patternOutputStream: DataStream[(Boolean, Row)], patternOutputStreamType: (Array[String], Array[TypeInformation[_]])): DataStream[Row] = {
    val filteredDataStream = patternOutputStream.filter(new PatternOutputFilter(exceptionConfig, config, preSchemaMap))
    filteredDataStream.map(_._2)(rowMapFunc(patternOutputStreamType._1, patternOutputStreamType._2))
  }

  private def doLookup(transformedStream: DataStream[Row], element: SwiftsSql, index: Int): DataStream[Row] = {
    val lookupSchemaMap = LookupHelper.getLookupSchemaMap(preSchemaMap, element)
    val fieldNames = FlinkSchemaUtils.getFieldNamesFromSchema(lookupSchemaMap)
    val fieldTypes = FlinkSchemaUtils.getFieldTypes(fieldNames, lookupSchemaMap)
    val resultDataStreamSeq = transformedStream.process(new LookupProcessElement(element, preSchemaMap, LookupHelper.getDbOutPutSchemaMap(element), ConnectionMemoryStorage.getDataStoreConnectionsMap, exceptionConfig, lookupTag))
    val resultDataStream = resultDataStreamSeq.flatMap(o => o)(rowMapFunc(fieldNames, fieldTypes))
    preSchemaMap = lookupSchemaMap
    println(resultDataStream.dataType.toString + "in doLookup")
    val exceptionStream: DataStream[String] = resultDataStreamSeq.getSideOutput(lookupTag)
    exceptionStream.map(new ExceptionProcess(exceptionConfig.exceptionProcessMethod, config, exceptionConfig))
    resultDataStream
  }
}
