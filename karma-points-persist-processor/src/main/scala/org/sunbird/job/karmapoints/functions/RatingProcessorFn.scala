package org.sunbird.job.karmapoints.functions

import com.fasterxml.jackson.core.JsonProcessingException
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.ObjectMapper
import org.apache.flink.streaming.api.functions.{KeyedProcessFunction, ProcessFunction}
import org.sunbird.job.karmapoints.domain.Event
import org.sunbird.job.karmapoints.task.KarmaPointsProcessorConfig
import org.sunbird.job.karmapoints.util.Utility._
import org.sunbird.job.util.{CassandraUtil, HttpUtil}
import org.sunbird.job.{BaseProcessFunction, BaseProcessKeyedFunction, Metrics}

import java.util

class RatingProcessorFn(config: KarmaPointsProcessorConfig, httpUtil: HttpUtil)
                       (implicit val stringTypeInfo: TypeInformation[String],
                                @transient var cassandraUtil: CassandraUtil = null)
  extends BaseProcessFunction[Event, String](config)   {

  lazy private val mapper: ObjectMapper = new ObjectMapper()

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    cassandraUtil = new CassandraUtil(config.dbHost, config.dbPort)
  }

  override def close(): Unit = {
    cassandraUtil.close()
    super.close()
  }

  override def metricsList(): List[String] = {
    List(config.totalEventsCount, config.dbReadCount, config.dbUpdateCount, config.failedEventCount, config.skippedEventCount, config.successEventCount,
      config.cacheHitCount, config.karmaPointsIssueEventsCount, config.cacheMissCount)
  }

  override def processElement(event: Event,
                              context: ProcessFunction[Event, String]#Context,
                              metrics: Metrics): Unit = {
    val usrId = event.getMap().get(config.USER_UNDERSCORE_ID).asInstanceOf[String]
    val activity_id = event.getMap().get(config.ACTIVITY_ID).asInstanceOf[String]
    if(doesEntryExist(usrId,config.OPERATION_TYPE_RATING,config.OPERATION_TYPE_RATING,activity_id)(metrics,config, cassandraUtil))
      return
    kpOnUserRating(usrId , config.OPERATION_TYPE_RATING ,config.OPERATION_TYPE_RATING,activity_id)(metrics)
  }

  private def kpOnUserRating(userId : String, contextType : String, operationType:String, contextId:String)(metrics: Metrics) :Unit = {
    val points: Int = config.ratingQuotaKarmaPoints
    val addInfoMap = new util.HashMap[String, AnyRef]
    val hierarchy: java.util.Map[String, AnyRef] = fetchContentHierarchy(contextId)( metrics,config, cassandraUtil)
    if(null == hierarchy || hierarchy.size() < 1)
      return
    addInfoMap.put(config.ADDINFO_COURSENAME, hierarchy.get(config.name))
    var addInfo = config.EMPTY
    try addInfo = mapper.writeValueAsString(addInfoMap)
    catch {
      case e: JsonProcessingException =>
        throw new RuntimeException(e)
    }
    insertKarmaPoints(userId, contextType ,operationType,contextId,points,addInfo)(metrics,config, cassandraUtil)
    updateKarmaSummary(userId, points)( config, cassandraUtil)
  }
}