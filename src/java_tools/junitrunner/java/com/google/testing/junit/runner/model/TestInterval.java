package com.google.testing.junit.runner.model;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import com.google.common.annotations.VisibleForTesting;

/**
 * Implementation of an immutable time interval, representing a period of time between two isntants.
 *
 * This class is thread-safe and immutable.
 */
final class TestInterval {
  private final long startInstant;
  private final long endInstant;

  TestInterval(long startInstant, long endInstant) {
    if (startInstant > endInstant) {
      throw new IllegalArgumentException("Start must be before end");
    }
    this.startInstant = startInstant;
    this.endInstant = endInstant;
  }

  long getStartMillis() {
    return startInstant;
  }

  long getEndMillis() {
    return endInstant;
  }

  long toDurationMillis() {
    return endInstant - startInstant;
  }

  TestInterval withEndMillis(long millis) {
    return new TestInterval(startInstant, millis);
  }

  String startInstantToString() {
    // Format as ISO8601 string
    return startInstantToString(TimeZone.getDefault());
  }

  /**
   * Exposed for testing because java Date does not allow setting of timezones.
   */
  @VisibleForTesting
  String startInstantToString(TimeZone tz) {
    DateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    format.setTimeZone(tz);
    return format.format(new Date(startInstant));
  }
}
