package org.threadly.load.gui;

import static org.junit.Assert.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.eclipse.swt.graphics.Point;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.load.gui.AmbushGraph.GuiPoint;

@SuppressWarnings("javadoc")
public class AmbushGraphGuiPointTest {
  private static final int X_SIZE = 1024;
  private static final int Y_SIZE = 768;
  
  private GuiPoint guiPoint;
  
  @Before
  public void setup() {
    guiPoint = new GuiPoint(null, new Point(X_SIZE, Y_SIZE), 
                            new HashMap<Integer, List<GuiPoint>>(), 1, 1);
    guiPoint.xRegionCountMap.put(1, Collections.singletonList(guiPoint));
  }
  
  @After
  public void cleanup() {
    guiPoint = null;
  }
  
  @Test
  public void constructorTest() {
    assertFalse(guiPoint.coordiantesSet);
  }
  
  @Test
  public void getXTest() {
    int x = guiPoint.getX();
    assertTrue(x > 0);
    assertTrue(x < X_SIZE);
    assertNull(guiPoint.xRegionCountMap);
  }
  
  @Test
  public void getYTest() {
    int y = guiPoint.getY();
    assertTrue(y > 0);
    assertTrue(y < Y_SIZE);
    assertNull(guiPoint.xRegionCountMap);
  }
  
  @Test
  public void setPositionTest() {
    int pos = 10;
    guiPoint.setPosition(pos, pos);
    
    assertTrue(guiPoint.coordiantesSet);
    assertEquals(pos, guiPoint.getX());
    assertEquals(pos, guiPoint.getY());
  }
}
