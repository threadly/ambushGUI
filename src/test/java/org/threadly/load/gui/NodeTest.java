package org.threadly.load.gui;

import static org.junit.Assert.*;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.threadly.util.StringUtils;

@SuppressWarnings("javadoc")
public class NodeTest {
  private static final String TEST_NODE_NAME = StringUtils.randomString(5);
  
  private Node joinNode;
  private Node node;
  
  @Before
  public void setup() {
    joinNode = new Node();
    node = new Node(TEST_NODE_NAME);
  }
  
  @After
  public void clean() {
    joinNode = null;
    node = null;
  }
  
  @Test
  public void isJoinNodeTest() {
    assertTrue(joinNode.isJoinNode());
    assertFalse(node.isJoinNode());
  }
  
  @Test
  public void getNameTest() {
    assertTrue(joinNode.getName().isEmpty());
    assertEquals(TEST_NODE_NAME, node.getName());
  }
  
  @Test
  public void addChildNodeTest() {
    node.addChildNode(joinNode);
    assertTrue(node.getChildNodes().contains(joinNode));
    assertTrue(joinNode.parentNodes.contains(node));
  }
  
  @Test
  public void deleteFromGraphTest() {
    node.addChildNode(joinNode);
    joinNode.deleteFromGraph();
    assertFalse(node.getChildNodes().contains(joinNode));
  }
}
