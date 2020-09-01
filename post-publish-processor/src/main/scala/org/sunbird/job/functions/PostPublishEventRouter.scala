package org.sunbird.job.functions

import java.lang.reflect.Type
import java.util

import com.datastax.driver.core.querybuilder.QueryBuilder
import com.google.gson.reflect.TypeToken
import org.apache.commons.collections.CollectionUtils
import org.apache.commons.lang3.StringUtils
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.streaming.api.functions.ProcessFunction
import org.slf4j.LoggerFactory
import org.sunbird.job.{BaseProcessFunction, Metrics}
import org.sunbird.job.task.PostPublishProcessorConfig
import org.sunbird.job.util.{CassandraUtil, HttpUtil}

import scala.collection.JavaConverters._

case class PublishMetadata(identifier: String, contentType: String, mimeType: String, pkgVersion: Int)

class PostPublishEventRouter(config: PostPublishProcessorConfig, httpUtil: HttpUtil)
                            (implicit val stringTypeInfo: TypeInformation[String],
                             @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[java.util.Map[String, AnyRef], String](config) {

  private[this] val logger = LoggerFactory.getLogger(classOf[PostPublishEventRouter])
  val mapType: Type = new TypeToken[java.util.Map[String, AnyRef]]() {}.getType
  lazy private val mapper: ObjectMapper = new ObjectMapper()

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def processElement(event: java.util.Map[String, AnyRef], context: ProcessFunction[java.util.Map[String, AnyRef], String]#Context, metrics: Metrics): Unit = {
    val eData = event.get("edata").asInstanceOf[java.util.Map[String, AnyRef]]
    val action = eData.getOrDefault("action", "").asInstanceOf[String]
    val mimeType = eData.getOrDefault("mimeType", "").asInstanceOf[String]
    val identifier = eData.getOrDefault("identifier", "").asInstanceOf[String]
    if (StringUtils.equals("application/vnd.ekstep.content-collection", mimeType) && StringUtils.equals(action, "post-publish-process")) {
      // Check shallow copied contents and publish.
      val shallowCopied = getShallowCopiedContents(identifier)
      logger.info("Shallow copied by this content - " + identifier + " are: " + shallowCopied.size)
      if (shallowCopied.size > 0) {
        val shallowCopyInput = new util.HashMap[String, AnyRef](eData) {{ put("shallowCopied", shallowCopied)}}
        context.output(config.shallowContentPublishOutTag, shallowCopyInput)
      }
      // Validate and trigger batch creation.
      if (!isBatchExists(identifier)) context.output(config.batchCreateOutTag, eData)
      // Check DIAL Code exist or not and trigger create and link.
      context.output(config.linkDIALCodeOutTag, eData)

    } else {
      metrics.incCounter(config.skippedEventCount)
    }
    metrics.incCounter(config.totalEventsCount)
  }

  override def metricsList(): List[String] = {
    List(config.successEventCount, config.failedEventCount, config.skippedEventCount, config.totalEventsCount)
  }

  def getShallowCopiedContents(identifier: String): List[PublishMetadata] = {
    val httpRequest = s"""{"request":{"filters":{"status":["Draft","Review","Live","Unlisted"],"origin":"${identifier}"},"fields":["identifier","mimeType","contentType","versionKey","channel","status","pkgVersion","lastPublishedBy","origin","originData"]}}"""
    val httpResponse = httpUtil.post(config.searchBaseUrl + "/v3/search", httpRequest)
    if (httpResponse.status == 200) {
      val response = mapper.readValue(httpResponse.body, classOf[java.util.Map[String, AnyRef]])
      val result = response.getOrDefault("result", new java.util.HashMap[String, AnyRef]()).asInstanceOf[java.util.Map[String, AnyRef]]
      val contents = result.getOrDefault("content", new java.util.ArrayList[java.util.Map[String, AnyRef]]()).asInstanceOf[java.util.List[java.util.Map[String, AnyRef]]]
      contents.asScala.filter(c => c.containsKey("originData"))
        .filter(c => {
          val originDataStr = c.getOrDefault("originData", "{}").asInstanceOf[String]
          val originData = mapper.readValue(originDataStr, classOf[java.util.Map[String, AnyRef]])
          val copyType = originData.getOrDefault("copyType", "deep").asInstanceOf[String]
          (StringUtils.equalsIgnoreCase(copyType, "shallow"))
        }).map(c => {
        val copiedId = c.get("identifier").asInstanceOf[String]
        val copiedMimeType = c.get("mimeType").asInstanceOf[String]
        val copiedPKGVersion = c.getOrDefault("pkgVersion", 0.asInstanceOf[AnyRef]).asInstanceOf[Number]
        val copiedContentType = c.get("contentType").asInstanceOf[String]
        PublishMetadata(copiedId, copiedContentType, copiedMimeType, copiedPKGVersion.intValue())
      }).toList
    }else {
      throw new Exception("Content search failed for shallow copy check:" + identifier)
    }
  }

  def isBatchExists(identifier: String): Boolean = {
    val selectQuery = QueryBuilder.select().all().from(config.lmsKeyspaceName, config.batchTableName)
    selectQuery.where.and(QueryBuilder.eq("courseid", identifier))
    val rows = cassandraUtil.find(selectQuery.toString)
    if (CollectionUtils.isNotEmpty(rows)) {
      val activeBatches = rows.asScala.filter(row => {
        val enrolmentType = row.getString("enrollmenttype")
        val status = row.getInt("status")
        (StringUtils.equalsIgnoreCase(enrolmentType, "Open") && (0 == status || 1 == status))
      }).toList
      if (activeBatches.nonEmpty)
        logger.info("Collection has a active batch: " + activeBatches.head.toString)
      activeBatches.nonEmpty
    } else false
  }

}
