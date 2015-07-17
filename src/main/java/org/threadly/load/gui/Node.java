package org.threadly.load.gui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.threadly.util.Clock;
import org.threadly.util.StringUtils;

/**
 * <p>Class which represents a node on the graph.</p>
 * 
 * @author jent - Mike Jensen
 */
public class Node {
  private static final String JOIN_NAME = StringUtils.randomString(5);  // can be short due to identity comparison

  protected final String name;
  // private to ensure changes are recorded in modificationCount
  private final ArrayList<Node> parents;
  private final ArrayList<Node> children;
  private int lastCleanChangeCount = Integer.MIN_VALUE;
  private int modificationCount = lastCleanChangeCount + 1; // should be incremented any time stored data changes
  
  /**
   * Constructs a new graph node with a specified identifier.  This is a node which is a branch or 
   * fork point.
   */
  public Node() {
    this(JOIN_NAME);
  }
  
  /**
   * Constructs a new graph node with a specified identifier.
   * 
   * @param name Identifier for this node
   */
  public Node(String name) {
    this.name = name;
    children = new ArrayList<Node>(2);
    parents = new ArrayList<Node>(2);
  }
  
  /**
   * Gets the name this node was constructed with.
   * 
   * @return Name of this node
   */
  public String getName() {
    if (isJoinNode()) {
      return "";
    } else {
      return name;
    }
  }
  
  /**
   * Indicates this node is a node where multiple nodes join into.  This indicates only a sync 
   * point where all nodes must reach before moving on to any child nodes.
   * 
   * @return {@code True} indicates a sync point in the graph
   */
  public boolean isJoinNode() {
    // we do an identify comparison here for efficiency as well as to avoid name conflicts
    return JOIN_NAME == name;
  }
  
  @Override
  public String toString() {
    return "node:" + name;
  }
  
  /**
   * Adds a node to be represented as a child node to this current instance.
   * 
   * @param node Node to be added as a child
   */
  public void addChildNode(Node node) {
    if (! children.contains(node)) {
      node.addParent(this);
      children.add(node);
      modificationCount++;
    }
  }
  
  protected void addParent(Node node) {
    if (! parents.contains(node)) {
      parents.add(node);
      modificationCount++;
    }
  }
  
  protected boolean removeChildNode(Node node) {
    if (children.remove(node)) {
      modificationCount++;
      return true;
    } else {
      return false;
    }
  }
  
  protected boolean removeParentNode(Node node) {
    if (parents.remove(node)) {
      modificationCount++;
      return true;
    } else {
      return false;
    }
  }
  
  /**
   * A collection of child nodes attached to this node.
   * 
   * @return A collection of child node references
   */
  public List<Node> getChildNodes() {
    return Collections.unmodifiableList(children);
  }
  
  /**
   * A collection of parent nodes which attach to this node.
   * 
   * @return A collection of parent node references
   */
  public List<Node> getParentNodes() {
    return Collections.unmodifiableList(parents);
  }

  /**
   * Removes this node from the graph.
   */
  protected void deleteFromGraph() {
    for (Node n: parents) {
      n.removeChildNode(this);
    }
    for (Node n: children) {
      n.removeParentNode(this);
    }
  }

  /**
   * Traverses and cleans the graph.  This cleans up duplicate information like multiple join points.
   */
  public void cleanGraph() {
    long startTime = Clock.lastKnownForwardProgressingMillis();
    while (true) {
      try {
        doCleanGraph();
        break;
      } catch (StackOverflowError e) {
        System.err.println("Attempting to clean graph..." + 
                             (Clock.lastKnownForwardProgressingMillis() - startTime) / 1000 + "seconds");
      }
    }
  }
  
  private void doCleanGraph() {
    if (lastCleanChangeCount != modificationCount) {
      if (! isJoinNode()) {
        // removes parent node if our node can function as join node
        if (parents.size() == 1) {
          Node parentNode = parents.get(0);
          if (parentNode.isJoinNode() && parentNode.children.size() < 2) {
            for (Node n : parentNode.parents) {
              n.removeChildNode(parentNode);
              n.addChildNode(this);
            }
            parentNode.deleteFromGraph();
          }
        }
      } else {
        // loops till consistent state, break at bottom
        while (true) {
          // removes tail node on graph that has no children and is a synthetic join node
          if (children.isEmpty()) {
            deleteFromGraph();
            lastCleanChangeCount = modificationCount;
            return;
          } else if (parents.size() == 1) {
            // remove this node and instead connect parent node to our children
            Node parentNode = parents.get(0);
            for (Node childNode : children) {
              parentNode.addChildNode(childNode);
            }
            parentNode.removeChildNode(this);
            lastCleanChangeCount = modificationCount;
            parentNode.doCleanGraph();
            return;
          }
          // if all child nodes are join nodes, make this node function as the join node
          boolean modifiedNodes = false;
          List<Node> originalNodes;
          int startChangeCount;
          do {
            startChangeCount = modificationCount;
            boolean allChildrenAreJoinNodes = ! children.isEmpty();
            for (Node n : children) {
              if (! n.isJoinNode()) {
                allChildrenAreJoinNodes = false;
                break;
              }
            }
            if (allChildrenAreJoinNodes) {
              originalNodes = new ArrayList<Node>(children);
              for (Node childNode: originalNodes) {
                if (childNode.parents.size() == 1) {
                  for (Node childsChild : childNode.children) {
                    addChildNode(childsChild);
                  }
                  modifiedNodes = removeChildNode(childNode) || 
                                    modifiedNodes || ! childNode.children.isEmpty();
                }
              }
            } else {
              originalNodes = null;
            }
          } while (originalNodes != null && startChangeCount != modificationCount);  // loop through all join only nodes
          if (modifiedNodes) {
            continue; // restart check if children change
          } else {
            break;
          }
        }
      }
      lastCleanChangeCount = modificationCount;
      
      // traverse to all child nodes to allow them to inspect themselves
      tillConsistent: while (true) {
        int startChangeCount = modificationCount;
        for (Node childNode: children) {
          childNode.doCleanGraph();
          if (startChangeCount != modificationCount) {
            continue tillConsistent;
          }
        }
        break;
      }

      lastCleanChangeCount = modificationCount;
      
      // cleanup memory if possible
      children.trimToSize();
      parents.trimToSize();
    }
  }
}
