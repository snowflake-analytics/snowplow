/*
 * Copyright (c) 2017 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics
package snowplow
package enrich
package common
package adapters
package registry

// Java
import com.fasterxml.jackson.core.JsonParseException

// Scalaz
import scalaz.Scalaz._

// json4s
import org.json4s._
import org.json4s.jackson.JsonMethods._

// Iglu
import com.snowplowanalytics.iglu.client.{Resolver, SchemaKey}

// Joda Time
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}

// This project
import com.snowplowanalytics.snowplow.enrich.common.loaders.CollectorPayload
import com.snowplowanalytics.snowplow.enrich.common.utils.{JsonUtils => JU}

/**
 * Transforms a collector payload which conforms to
 * a known version of the Mailchimp Tracking webhook
 * into raw events.
 */
object MarketoAdapter extends Adapter {

  // Vendor name for Failure Message
  private val VendorName = "Marketo"

  // Expected content type for a request body
  private val ContentType = "application/json"

  // Tracker version for an Mailchimp Tracking webhook
  private val TrackerVersion = "com.marketo-v1"

  // Schemas for reverse-engineering a Snowplow unstructured event
  private val EventSchemaMap = Map (
    "event"   -> SchemaKey("com.marketo", "event", "jsonschema", "1-0-0").toSchemaUri 
  )

  // Datetime format used by Marketo
  private val MarketoDateTimeFormat = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss").withZone(DateTimeZone.UTC)

  private def payloadBodyToEvent(json: String, payload: CollectorPayload): Validated[RawEvent] = {

    try {
      val parsed = parse(json)

      val parsed_converted = parsed transformField {
        case ("acquisition_date", JString(value))         => ("acquisition_date", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("created_at", JString(value))               => ("created_at", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("email_suspended_at", JString(value))       => ("email_suspended_at", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("last_referred_enrollment", JString(value)) => ("last_referred_enrollment", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("last_referred_visit", JString(value))      => ("last_referred_visit", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("updated_at", JString(value))               => ("updated_at", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
        case ("datetime", JString(value))                 => ("datetime", JString(JU.toJsonSchemaDateTime(value, MarketoDateTimeFormat)))
    }

      val eventType = Some("event")

      lookupSchema(eventType, VendorName, EventSchemaMap) map {
        schema => RawEvent(
            api = payload.api,
            parameters = toUnstructEventParams(
              TrackerVersion,
              toMap(payload.querystring),
              schema,
              parsed_converted,
              "srv"
            ),
            contentType = payload.contentType,
            source = payload.source,
            context = payload.context
          )
      }

    } catch {
      case e: JsonParseException => {
        val exception = JU.stripInstanceEtc(e.toString).orNull
        s"$VendorName event failed to parse into JSON: [$exception]".failNel
      }
    }
  }

  /**
   * Converts a CollectorPayload instance into raw events.
   * Marketo event contains no "type" field and since there's only 1 schema the function lookupschema takes the eventType parameter as "event".
   * We expect the type parameter to match the supported events, else
   * we have an unsupported event type.
   *
   * @param payload The CollectorPayload containing one or more
   *        raw events as collected by a Snowplow collector
   * @param resolver (implicit) The Iglu resolver used for
   *        schema lookup and validation. Not used
   * @return a Validation boxing either a NEL of RawEvents on
   *         Success, or a NEL of Failure Strings
   */
  def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents =
    (payload.body, payload.contentType) match {
      case (None, _) => s"Request body is empty: no ${VendorName} event to process".failNel
      case (Some(body), _) => {
        val event = payloadBodyToEvent(body, payload)
        rawEventsListProcessor(List(event))
      }
    }
}