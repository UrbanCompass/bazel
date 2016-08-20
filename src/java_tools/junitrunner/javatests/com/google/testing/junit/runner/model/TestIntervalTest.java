package com.google.testing.junit.runner.model;

import java.util.Date;
import java.util.TimeZone;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class TestIntervalTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();

  @Test
  public void testCreation() {
    TestInterval interval = new TestInterval(123456, 234567);
    assertEquals(123456, interval.getStartMillis());
    assertEquals(234567, interval.getEndMillis());
  }

  @Test
  public void testCreationFailure() {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Start must be before end");
    new TestInterval(35, 23);
  }

  @Test
  public void testToDuration() {
    assertEquals(100, new TestInterval(50, 150).toDurationMillis());
    assertEquals(0, new TestInterval(100, 100).toDurationMillis());
  }

  @Test
  public void testDateFormat() {
    Date date = new Date(1471709734000L);
    TestInterval interval = new TestInterval(date.getTime(), date.getTime() + 100);
    assertEquals("2016-08-20T12:15:34.000-04:00", interval.startInstantToString(
        TimeZone.getTimeZone("America/New_York")));
    assertEquals("2016-08-20T16:15:34.000Z", interval.startInstantToString(
        TimeZone.getTimeZone("GMT")));
  }
}
