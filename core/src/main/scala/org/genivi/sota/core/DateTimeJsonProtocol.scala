/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import org.joda.time.{ DateTimeZone, DateTime, Interval }
import org.joda.time.format.ISODateTimeFormat
import spray.json._

trait DateTimeJsonProtocol extends DefaultJsonProtocol {
  implicit object DateTimeJsonFormat extends RootJsonFormat[DateTime] {
    private lazy val format = ISODateTimeFormat.dateTimeNoMillis()
    def write(datetime: DateTime): JsValue = JsString(format.print(datetime.withZone(DateTimeZone.UTC)))
    def read(json: JsValue): DateTime = json match {
      case JsString(x) => try { format.parseDateTime(x) }
                          catch {
                            case _ : IllegalArgumentException => deserializationError(s"Cannot parse $x as DateTime")
                          }
      case x           => deserializationError("Expected DateTime as JsString, but got " + x)
    }
  }

  implicit object DateTimeIntervalJsonFormat extends JsonFormat[Interval] {
    def write(interval: Interval): JsValue = JsObject(
      "start" -> DateTimeJsonFormat.write(interval.getStart),
      "end" -> DateTimeJsonFormat.write(interval.getEnd))

    def read(json: JsValue): Interval =
      json.asJsObject.getFields("start", "end") match {
        case Seq(start, end) => new Interval(
          DateTimeJsonFormat.read(start),
          DateTimeJsonFormat.read(end)
        )
        case x => deserializationError("Expected Interval as an object with 'start' and 'end' Datetimes as JsStrings, but got " + x)
      }
  }
}

object DateTimeJsonProtocol extends DateTimeJsonProtocol
