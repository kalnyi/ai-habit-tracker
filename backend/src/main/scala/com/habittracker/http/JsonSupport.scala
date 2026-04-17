package com.habittracker.http

import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport

/** Mixes in FailFastCirceSupport from akka-http-circe.
  *
  * Traits that mix in JsonSupport gain implicit
  * FromRequestUnmarshaller and ToResponseMarshaller instances for all
  * Circe-encoded types. The "FailFast" variant rejects requests at the
  * first JSON decoding error rather than accumulating errors, keeping
  * error responses simple.
  */
trait JsonSupport extends FailFastCirceSupport
