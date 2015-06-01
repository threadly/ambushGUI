package org.threadly.load.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DragDetectEvent;
import org.eclipse.swt.events.DragDetectListener;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.MouseMoveListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.threadly.concurrent.PrioritySchedulerInterface;
import org.threadly.util.ArgumentVerifier;
import org.threadly.util.Clock;

/**
 * <p>Class which handles drawing a window to show a given graph.</p>
 *
 * @author jent - Mike Jensen
 */
public class AmbushGraph {
  private static final int LARGE_X_SIZE = 1900;
  private static final int LARGE_Y_SIZE = 1400;
  private static final int SMALL_X_SIZE = 1280;
  private static final int SMALL_Y_SIZE = 1024;
  private static final int PREVIEW_X_SIZE = 640;
  private static final int DRAG_TOLLERANCE = 25;
  private static final int HIGHLIGHT_DISAPEAR_DELAY = 2000;
  private static final int BACKGROUND_GRAY = 210;
  private static final int GRID_SOFTNESS = 100;  // randomness for point placement
  private static final int DISTANCE_FROM_EDGE = 75;  // dots wont be placed within this distance from the edge
  private static final int SQUEEZE_FACTOR = 4;  // smaller numbers result in tighter plot groups
  private static final int MAX_NODES_DRAW_ALL_NAMES = 20;
  private static final Random RANDOM = new Random(Clock.lastKnownTimeMillis());

  private final PrioritySchedulerInterface scheduler;
  private final Color backgroundColor;
  private final Shell mainShell;
  private final Shell previewShell;
  private final Runnable redrawRunnable;
  private volatile GraphDataSet currentDataSet;

  /**
   * Constructs a new window which will display the graph of nodes.  Nodes will be provided via
   * {@link #updateGraphModel(Node)}.
   *
   * @param scheduler Scheduler to schedule and execute tasks on to
   * @param display A non-disposed display to open the shell on
   */
  public AmbushGraph(PrioritySchedulerInterface scheduler, Display display) {
    this(scheduler, display, -1, -1);
  }

  /**
   * Constructs a new window which will display the graph of nodes.  Nodes will be provided via
   * {@link #updateGraphModel(Node)}.  This constructor allows you to specify the original window
   * size.
   *
   * @param scheduler Scheduler to schedule and execute tasks on to
   * @param display A non-disposed display to open the shell on
   * @param xSize Width in pixels for the window
   * @param ySize Height in pixels for the window
   */
  public AmbushGraph(PrioritySchedulerInterface scheduler, Display display, int xSize, int ySize) {
    ArgumentVerifier.assertNotNull(scheduler, "scheduler");

    if (xSize < 1 || ySize < 1) {
      Rectangle displayBounds = display.getBounds();
      if (displayBounds.width > LARGE_X_SIZE && displayBounds.height > LARGE_Y_SIZE) {
        xSize = LARGE_X_SIZE;
        ySize = LARGE_Y_SIZE;
      } else {
        xSize = SMALL_X_SIZE;
        ySize = SMALL_Y_SIZE;
      }
    }
    redrawRunnable = new Runnable() {
      private final AtomicBoolean displayTaskExeced = new AtomicBoolean();

      @Override
      public void run() {
        if (! mainShell.isDisposed() && ! mainShell.getDisplay().isDisposed()) {
          if (displayTaskExeced.compareAndSet(false, true)) {
            mainShell.getDisplay().asyncExec(new Runnable() {
              @Override
              public void run() {
                displayTaskExeced.set(false);
                redraw();
              }
            });
          }
        } else {
          AmbushGraph.this.scheduler.remove(this);
        }
      }
    };

    this.scheduler = scheduler;
    backgroundColor = new Color(display, BACKGROUND_GRAY, BACKGROUND_GRAY, BACKGROUND_GRAY);

    mainShell = new Shell(display);
    mainShell.setText("Ambush execution graph");
    mainShell.setSize(xSize, ySize);
    mainShell.setBackground(backgroundColor);

    mainShell.addListener(SWT.Paint, new Listener() {
      @Override
      public void handleEvent(Event arg0) {
        updateDisplay(arg0.gc, false);
      }
    });
    new MainWindowListener().registerListener();

    previewShell = new Shell(display);
    previewShell.setText("Ambush preview");
    previewShell.setSize(PREVIEW_X_SIZE, (int)(PREVIEW_X_SIZE * ((double)ySize) / xSize));
    previewShell.setBackground(backgroundColor);

    previewShell.addListener(SWT.Paint, new Listener() {
      @Override
      public void handleEvent(Event arg0) {
        updateDisplay(arg0.gc, true);
      }
    });
    new PreviewWindowListener().registerListener();

    currentDataSet = new GraphDataSet(xSize, ySize);
  }

  /**
   * Opens the shell and handles doing the read and dispatch loop for the display.  This call will
   * block until the shell is closed.
   */
  public void runGuiLoop() {
    mainShell.open();
    previewShell.open();

    while (! mainShell.isDisposed()) {
      Display disp = mainShell.getDisplay();
      if (! disp.readAndDispatch()) {
        disp.sleep();
      }
    }
  }

  /**
   * Updates the graph representation.  This call will start crawling from the head node provided
   * to explore all child nodes.
   *
   * @param headNode Node to start building graph from
   */
  public void updateGraphModel(Node headNode) {
    Map<Node, GuiPoint> buildingMap = new HashMap<Node, GuiPoint>();
    Map<Integer, List<GuiPoint>> xRegionCountMap = new HashMap<Integer, List<GuiPoint>>();
    GraphDataSet newDataSet = new GraphDataSet(currentDataSet.mainBounds.x, currentDataSet.mainBounds.y);
    traverseNode(newDataSet, headNode, buildingMap, 1, 1, new AtomicInteger(), xRegionCountMap);

    // cleanup xRegionCountMap
    int maxYCount = 0;
    Iterator<List<GuiPoint>> it = xRegionCountMap.values().iterator();
    while (it.hasNext()) {
      List<GuiPoint> xRegion = it.next();
      Collections.sort(xRegion, new Comparator<GuiPoint>() {
        @Override
        public int compare(GuiPoint o1, GuiPoint o2) {
          return o1.yRegion - o2.yRegion;
        }
      });
      Iterator<GuiPoint> points = xRegion.iterator();
      int currentPoint = 0;
      while (points.hasNext()) {
        points.next().yRegion = ++currentPoint;
      }
      if (currentPoint > maxYCount) {
        maxYCount = currentPoint;
      }
    }

    newDataSet.setData(buildingMap);
    // TODO - make zooming smarter
    if (xRegionCountMap.size() > 50) {
      newDataSet.mainBounds.x = mainShell.getSize().x * 4;
    } else if (xRegionCountMap.size() > 10) {
      newDataSet.mainBounds.x = mainShell.getSize().x * 2;
    } else {
      newDataSet.mainBounds.x = mainShell.getSize().x;
    }
    if (maxYCount > 50) {
      newDataSet.mainBounds.y = mainShell.getSize().y * 4;
    } else if (maxYCount > 10) {
      newDataSet.mainBounds.y = mainShell.getSize().y * 2;
    } else {
      newDataSet.mainBounds.y = mainShell.getSize().y;
    }
    // can only squeeze after mainBounds have been updated
    newDataSet.squeezePoints(headNode);

    synchronized (this) {
      currentDataSet = newDataSet;

      if (zoomedIn()) {
        updateMainOrigin(0, currentDataSet.mainBounds.y / 2 - (mainShell.getSize().y / 2));
      }
      redrawRunnable.run();
    }
  }

  private void traverseNode(GraphDataSet newDataSet,
                            Node currentNode, Map<Node, GuiPoint> buildingMap,
                            int xRegion, int yRegion, AtomicInteger maxYRegion,
                            Map<Integer, List<GuiPoint>> xRegionCountMap) {
    if (maxYRegion.get() < yRegion) {
      maxYRegion.set(yRegion);
    }
    GuiPoint currentPoint = buildingMap.get(currentNode);
    if (currentPoint == null) {
      currentPoint = new GuiPoint(makeRandomColor(), newDataSet.mainBounds,
                                  xRegionCountMap, xRegion, yRegion);
      buildingMap.put(currentNode, currentPoint);
      add(currentPoint, xRegionCountMap);
      int childNodeRegion = maxYRegion.get();
      Iterator<Node> it = currentNode.getChildNodes().iterator();
      while (it.hasNext()) {
        traverseNode(newDataSet, it.next(), buildingMap,
                     xRegion + 1, ++childNodeRegion, maxYRegion, xRegionCountMap);
      }
    } else {
      if (xRegion > currentPoint.xRegion) {
        Set<Node> inspectedNodes = new HashSet<Node>();
        inspectedNodes.add(currentNode);
        shiftLeft(currentNode, currentPoint, buildingMap,
                  xRegion - currentPoint.xRegion, xRegionCountMap, inspectedNodes);
      }
    }
  }

  private static void add(GuiPoint point, Map<Integer, List<GuiPoint>> map) {
    List<GuiPoint> currList = map.get(point.xRegion);
    if (currList == null) {
      currList = new LinkedList<GuiPoint>();
      map.put(point.xRegion, currList);
    }
    if (! currList.contains(point)) {
      currList.add(point);
    }
  }

  private static void remove(GuiPoint point, Map<Integer, List<GuiPoint>> map) {
    List<GuiPoint> currList = map.get(point.xRegion);
    if (currList != null) {
      currList.remove(point);
    }
  }

  private void shiftLeft(Node currNode, GuiPoint point,
                         Map<Node, GuiPoint> buildingMap, int shiftAmount,
                         Map<Integer, List<GuiPoint>> xRegionCountMap, Set<Node> shiftedNodes) {
    remove(point, xRegionCountMap);
    point.xRegion += shiftAmount;
    add(point, xRegionCountMap);
    Iterator<Node> it = currNode.getChildNodes().iterator();
    while (it.hasNext()) {
      Node child = it.next();
      if (shiftedNodes.contains(child)) {
        continue;
      } else {
        shiftedNodes.add(child);
      }
      GuiPoint childPoint = buildingMap.get(child);
      if (childPoint != null) {
        shiftLeft(child, childPoint, buildingMap, shiftAmount, xRegionCountMap, shiftedNodes);
      }
    }
  }

  private void updateDisplay(GC gc, boolean preview) {
    GraphDataSet dataSet = this.currentDataSet;
    //gc.setBackground(new Color(shell.getDisplay(), 230, 230, 230));
    //gc.fillRectangle(0, 0, XSIZE, YSIZE);
    Iterator<Entry<Node, GuiPoint>> it = dataSet.guiNodeMap.entrySet().iterator();
    while (it.hasNext()) {
      Entry<Node, GuiPoint> entry = it.next();
      // draw a dot to indicate node point
      gc.setForeground(entry.getValue().color);
      int pointX = entry.getValue().getX();
      int pointY = entry.getValue().getY();
      int size;
      if (preview) {
        double xFactor = ((double)previewShell.getSize().x) / dataSet.mainBounds.x;
        pointX = (int)(pointX * xFactor);
        double yFactor = ((double)previewShell.getSize().y) / dataSet.mainBounds.y;
        pointY = (int)(pointY * yFactor);
        size = 2;
      } else {
        pointX -= dataSet.mainOrigin.x;
        pointY -= dataSet.mainOrigin.y;
        size = 5;
      }
      gc.setBackground(entry.getValue().color);
      gc.fillOval(pointX, pointY, size, size);
      gc.setBackground(backgroundColor);

      // draw lines to peer nodes (which may or may not be drawn yet)
      Iterator<Node> it2 = entry.getKey().getChildNodes().iterator();
      while (it2.hasNext()) {
        Node child = it2.next();
        GuiPoint childPoint = dataSet.guiNodeMap.get(child);
        if (childPoint == null) {
          System.err.println("***** " + entry.getKey().getName() +
                               " is connected to an unknown node: " + child.getName() + " *****");
          continue;
        }
        int childX = childPoint.getX();
        int childY = childPoint.getY();
        if (preview) {
          double xFactor = ((double)previewShell.getSize().x) / dataSet.mainBounds.x;
          childX = (int)(childX * xFactor);
          double yFactor = ((double)previewShell.getSize().y) / dataSet.mainBounds.y;
          childY = (int)(childY * yFactor);
        } else {
          childX -= dataSet.mainOrigin.x;
          childY -= dataSet.mainOrigin.y;
        }

        gc.drawLine(pointX, pointY, childX, childY);
      }

      // Draw the label last
      if (! preview && (dataSet.drawAllNames || dataSet.highlightedPoint == entry.getValue())) {
        gc.setForeground(new Color(mainShell.getDisplay(), 0, 0, 0));
        gc.setBackground(backgroundColor);
        gc.drawText(entry.getKey().getName(), pointX + 10, pointY - 5);
      }
    }

    gc.setForeground(new Color(mainShell.getDisplay(), 0, 0, 0));
    if (preview) {
      if (zoomedIn()) {
        double xFactor = ((double)previewShell.getSize().x) / dataSet.mainBounds.x;
        double yFactor = ((double)previewShell.getSize().y) / dataSet.mainBounds.y;
        int translatedMainOriginX = (int)(dataSet.mainOrigin.x * xFactor);
        int translatedMainOriginY = (int)(dataSet.mainOrigin.y * yFactor);
        int translatedMainWidth = (int)(mainShell.getSize().x * xFactor);
        int translatedMainHeight = (int)(mainShell.getSize().y * yFactor);
        gc.drawRectangle(translatedMainOriginX, translatedMainOriginY,
                         translatedMainWidth, translatedMainHeight);
      }
    } else {
      if (dataSet.drawAllNames) {
        gc.drawText("Hide names", 10, 10);
      } else {
        gc.drawText("Show names", 10, 10);
      }
    }
  }

  private boolean zoomedIn() {
    if (Math.abs(currentDataSet.mainBounds.x - mainShell.getSize().x) > 10) {
      return true;
    } else if (Math.abs(currentDataSet.mainBounds.y - mainShell.getSize().y) > 10) {
      return true;
    } else {
      return false;
    }
  }

  private GuiPoint getClosestPoint(int x, int y) {
    GraphDataSet dataSet = this.currentDataSet;
    Iterator<GuiPoint> it = dataSet.guiNodeMap.values().iterator();
    GuiPoint minEntry = null;
    double minDistance = Double.MAX_VALUE;
    while (it.hasNext()) {
      GuiPoint point = it.next();
      if (Math.abs(point.getX() - dataSet.mainOrigin.x - x) <= DRAG_TOLLERANCE &&
          Math.abs(point.getY() - dataSet.mainOrigin.y - y) <= DRAG_TOLLERANCE) {
        double distance = Math.sqrt(Math.pow(Math.abs(point.getX() - dataSet.mainOrigin.x - x), 2) +
                                    Math.pow(Math.abs(point.getY() - dataSet.mainOrigin.y - y), 2));
        if (distance < minDistance) {
          minDistance = distance;
          minEntry = point;
        }
      }
    }
    return minEntry;
  }

  private void updateMainOrigin(int x, int y) {
    GraphDataSet dataSet = this.currentDataSet;
    if (x < 0) {
      x = 0;
    } else if (x + mainShell.getBounds().width > dataSet.mainBounds.x) {
      x = dataSet.mainBounds.x - mainShell.getBounds().width;
    }
    if (y < 0) {
      y = 0;
    } else if (y + mainShell.getBounds().height > dataSet.mainBounds.y) {
      y = dataSet.mainBounds.y - mainShell.getBounds().height;
    }
    dataSet.mainOrigin = new Point(x, y);

    redraw();
  }

  private void redraw() {
    if (! mainShell.isDisposed() && ! mainShell.getDisplay().isDisposed()) {
      if (mainShell.isVisible()) {
        mainShell.redraw();
      }
      if (! previewShell.isDisposed() && previewShell.isVisible()) {
        previewShell.redraw();
      }
    }
  }

  private Color makeRandomColor() {
    final int maxValue = 150;
    int r = RANDOM.nextInt(maxValue);
    int g = RANDOM.nextInt(maxValue);
    int b = RANDOM.nextInt(maxValue);
    return new Color(mainShell.getDisplay(), r, g, b);
  }

  private static int getSoftGridPoint(int region, int totalRegions, int maxDimension) {
    if (region < 1) {
      throw new IllegalArgumentException("Region must be >= 1: " + region);
    } else if (region > totalRegions) {
      throw new IllegalArgumentException("Region can not be beyond total regions: " + region + " / " + totalRegions);
    }
    int spacePerRegion = maxDimension / totalRegions;
    int pos = spacePerRegion / 2;
    pos += (region - 1) * spacePerRegion;
    int softness = RANDOM.nextInt(GRID_SOFTNESS);
    if (pos < DISTANCE_FROM_EDGE || (pos < maxDimension - DISTANCE_FROM_EDGE && RANDOM.nextBoolean())) {
      pos += softness;
    } else {
      pos -= softness;
    }
    if (pos < DISTANCE_FROM_EDGE) {
      pos = DISTANCE_FROM_EDGE;
    } else if (pos > maxDimension - DISTANCE_FROM_EDGE) {
      pos = maxDimension - DISTANCE_FROM_EDGE;
    }
    return pos;
  }

  /**
   * <p>This class handles all listener actions for the main window.</p>
   *
   * @author jent - Mike Jensen
   */
  private class MainWindowListener implements DragDetectListener, MouseListener, MouseMoveListener {
    public void registerListener() {
      mainShell.addDragDetectListener(this);
      mainShell.addMouseListener(this);
      mainShell.addMouseMoveListener(this);
    }

    @Override
    public void dragDetected(DragDetectEvent dde) {
      if (dde.button != 1) {
        return;
      }

      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      dataSet.movingPoint = getClosestPoint(dde.x, dde.y);
      if (dataSet.movingPoint == null && zoomedIn()) {
        dataSet.dragPoint = new Point(dde.x, dde.y);
      }
    }

    @Override
    public void mouseDoubleClick(MouseEvent me) {
      // ignored
    }

    @Override
    public void mouseDown(MouseEvent me) {
      if (me.button != 1) {
        return;
      }

      if (me.x < 150 && me.y < 50) {
        GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
        dataSet.drawAllNames = ! dataSet.drawAllNames;
        if (! dataSet.drawAllNames) {
          dataSet.highlightedPoint = null;
        }
        mainShell.redraw();
      }
    }

    @Override
    public void mouseUp(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      dataSet.movingPoint = null;
      dataSet.dragPoint = null;
    }

    @Override
    public void mouseMove(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (dataSet.dragPoint != null) {
        if (dataSet.dragPoint.x != me.x || dataSet.dragPoint.y != me.y) {
          updateMainOrigin(dataSet.mainOrigin.x + dataSet.dragPoint.x - me.x,
                           dataSet.mainOrigin.y + dataSet.dragPoint.y - me.y);
          dataSet.dragPoint = new Point(me.x, me.y);
        }
      } else if (dataSet.movingPoint != null) {
        dataSet.movingPoint.setPosition(Math.max(Math.min(me.x + dataSet.mainOrigin.x, dataSet.mainBounds.x - 25), 10),
                                        Math.max(Math.min(me.y + dataSet.mainOrigin.y, dataSet.mainBounds.y - 45), 10));

        redraw();
      } else if (! dataSet.drawAllNames) {
        GuiPoint previousHighlighted = dataSet.highlightedPoint;
        dataSet.highlightedPoint = getClosestPoint(me.x, me.y);
        if (previousHighlighted != dataSet.highlightedPoint) {
          if (dataSet.highlightedPoint != null) {
            mainShell.redraw();
          } else {
            scheduler.schedule(redrawRunnable, HIGHLIGHT_DISAPEAR_DELAY);
          }
        }
      }
    }
  }

  /**
   * <p>This class handles all listener actions for the preview window.</p>
   *
   * @author jent - Mike Jensen
   */
  private class PreviewWindowListener implements DragDetectListener, MouseListener, MouseMoveListener {
    public void registerListener() {
      previewShell.addDragDetectListener(this);
      previewShell.addMouseListener(this);
      previewShell.addMouseMoveListener(this);
    }

    @Override
    public void dragDetected(DragDetectEvent arg0) {
      if (arg0.button != 1 || ! zoomedIn()) {
        return;
      }

      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      double xFactor = ((double)previewShell.getSize().x) / dataSet.mainBounds.x;
      double yFactor = ((double)previewShell.getSize().y) / dataSet.mainBounds.y;
      int translatedMainOriginX = (int)(dataSet.mainOrigin.x * xFactor);
      int translatedMainOriginY = (int)(dataSet.mainOrigin.y * yFactor);
      int translatedMainWidth = (int)(mainShell.getSize().x * xFactor);
      int translatedMainHeight = (int)(mainShell.getSize().y * yFactor);
      if (arg0.x > translatedMainOriginX && arg0.x < translatedMainOriginX + translatedMainWidth &&
          arg0.y > translatedMainOriginY && arg0.y < translatedMainOriginY + translatedMainHeight) {
        dataSet.dragPoint = new Point(arg0.x, arg0.y);
      }
    }

    @Override
    public void mouseMove(MouseEvent me) {
      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      if (dataSet.dragPoint != null) {
        if (dataSet.dragPoint.x != me.x || dataSet.dragPoint.y != me.y) {
          double xFactor = ((double)dataSet.mainBounds.x) / previewShell.getSize().x;
          double yFactor = ((double)dataSet.mainBounds.y) / previewShell.getSize().y;
          updateMainOrigin((int)(dataSet.mainOrigin.x + (me.x - dataSet.dragPoint.x) * xFactor),
                           (int)(dataSet.mainOrigin.y + (me.y - dataSet.dragPoint.y) * yFactor));
          dataSet.dragPoint = new Point(me.x, me.y);
        }
      }
    }

    @Override
    public void mouseDoubleClick(MouseEvent me) {
      if (me.button != 1 || ! zoomedIn()) {
        return;
      }

      GraphDataSet dataSet = AmbushGraph.this.currentDataSet;
      double xFactor = ((double)dataSet.mainBounds.x) / previewShell.getSize().x;
      double yFactor = ((double)dataSet.mainBounds.y) / previewShell.getSize().y;
      updateMainOrigin((int)((me.x * xFactor) - (mainShell.getBounds().width / 2)),
                       (int)((me.y * yFactor) - (mainShell.getBounds().height / 2)));
    }

    @Override
    public void mouseDown(MouseEvent arg0) {
      // ignored
    }

    @Override
    public void mouseUp(MouseEvent arg0) {
      currentDataSet.dragPoint = null;
    }
  }

  /**
   * <p>Container of data which represents the state of the graph.</p>
   *
   * @author jent - Mike Jensen
   */
  protected static class GraphDataSet {
    protected final Point mainBounds;
    protected volatile Map<Node, GuiPoint> guiNodeMap;
    protected volatile boolean drawAllNames;
    protected volatile Point mainOrigin;
    private GuiPoint movingPoint;
    private Point dragPoint;
    private GuiPoint highlightedPoint;

    public GraphDataSet(int xSize, int ySize) {
      mainBounds = new Point(xSize, ySize);
      guiNodeMap = Collections.emptyMap();
      drawAllNames = true;
      mainOrigin = new Point(0, 0);
      movingPoint = null;
      dragPoint = null;
      highlightedPoint = null;
    }

    /**
     * Updates the stored data with the provided guiNodeMap.
     * 
     * @param guiNodeMap New map of nodes and points to store
     */
    public void setData(Map<Node, GuiPoint> guiNodeMap) {
      this.guiNodeMap = guiNodeMap;
      drawAllNames = guiNodeMap.size() <= MAX_NODES_DRAW_ALL_NAMES;
    }

    /**
     * Squeezes collections of points together.  This should only be called after the 
     * {@link #mainBounds} has been set.
     * 
     * @param headNode Node to start traversing graph from
     */
    public void squeezePoints(Node headNode) {
      // cluster the dots better
      List<Node> childNodes = new ArrayList<Node>();
      for (Node n: headNode.getChildNodes()) {
        childNodes.addAll(n.getChildNodes());
      }
      while (! childNodes.isEmpty()) {
        List<Node> newChildNodes = new ArrayList<Node>();
        Iterator<Node> it = childNodes.iterator();
        while (it.hasNext()) {
          Node childNode = it.next();
          GuiPoint childGp = guiNodeMap.get(childNode);
          if (childGp == null) {
            System.err.println("***** unknown node: " + childNode.getName() + " *****");
            continue;
          }
          int sampleSize = 0;
          int totalParentPos = 0;
          for (Node pNode : childNode.getParentNodes()) {
            GuiPoint gp = guiNodeMap.get(pNode);
            if (gp == null) {
              /* TODO - this is rather common due to deleted nodes which make
               * parts of the graph unable to be reached from child nodes
               */
              /*System.err.println("***** " + childNode.getName() +
                                 " is connected to an unknown node: " + pNode.getName() + " *****");*/
              continue;
            }
            sampleSize++;
            totalParentPos += gp.getY();
          }
          if (sampleSize > 0) {
            int moveDistance = ((totalParentPos / sampleSize) - childGp.getY()) / SQUEEZE_FACTOR;
            childGp.y += moveDistance;
          }
          for (Node n: childNode.getChildNodes()) {
            if (! newChildNodes.contains(n)) {
              newChildNodes.add(n);
            }
          }
        }
        childNodes = newChildNodes;
      }
    }
  }

  /**
   * <p>Class which stores information for used for drawing a node on the GUI.</p>
   *
   * @author jent - Mike Jensen
   */
  protected static class GuiPoint {
    protected final Color color;
    protected final Point mainBounds;
    protected Map<Integer, List<GuiPoint>> xRegionCountMap;
    protected int xRegion;
    protected int yRegion;
    protected boolean coordiantesSet;
    protected int x;
    protected int y;

    public GuiPoint(Color color, Point mainBounds,
                    Map<Integer, List<GuiPoint>> xRegionCountMap, int xRegion, int yRegion) {
      this.color = color;
      this.mainBounds = mainBounds;
      this.xRegionCountMap = xRegionCountMap;
      this.xRegion = xRegion;
      this.yRegion = yRegion;
      coordiantesSet = false;
    }

    private void ensureCoordinatesSet() {
      if (! coordiantesSet) {
        coordiantesSet = true;
        if (xRegion == 1) {
          x = DISTANCE_FROM_EDGE;
        } else {
          x = getSoftGridPoint(xRegion, xRegionCountMap.size(), mainBounds.x);
        }
        y = getSoftGridPoint(yRegion, xRegionCountMap.get(xRegion).size(), mainBounds.y);
        xRegionCountMap = null; // no longer needed, allow GC
      }
    }

    public int getX() {
      ensureCoordinatesSet();
      return x;
    }

    public int getY() {
      ensureCoordinatesSet();
      return y;
    }

    public void setPosition(int x, int y) {
      coordiantesSet = true;
      this.x = x;
      this.y = y;
    }
  }
}
