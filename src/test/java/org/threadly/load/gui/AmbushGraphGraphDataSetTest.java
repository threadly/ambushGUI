package org.threadly.load.gui;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.load.gui.AmbushGraph.GraphDataSet;

@SuppressWarnings("javadoc")
public class AmbushGraphGraphDataSetTest {
  private static final int X_SIZE = 1024;
  private static final int Y_SIZE = 768;
  private GraphDataSet dataSet;
  
  @Before
  public void setup() {
    dataSet = new GraphDataSet(X_SIZE, Y_SIZE);
  }
  
  @After
  public void cleanup() {
    dataSet = null;
  }
  
  @Test
  public void constructorTest() {
    assertEquals(X_SIZE, dataSet.mainBounds.x);
    assertEquals(Y_SIZE, dataSet.mainBounds.y);
    assertTrue(dataSet.guiNodeMap.isEmpty());
    assertEquals(0, dataSet.mainOrigin.x);
    assertEquals(0, dataSet.mainOrigin.y);
  }
  
  @Test
  public void squeezeNoOpTest() {
    dataSet.squeezePoints(new Node());
    // no exception should throw
  }
}
