package com.intellij.ui.tabs;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.ui.ShadowAction;
import com.intellij.openapi.Disposable;
import com.intellij.util.ui.GraphicsConfig;
import com.intellij.util.ui.UIUtil;
import com.intellij.ui.CaptionPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeEvent;
import java.util.*;
import java.util.List;
import java.awt.*;
import java.awt.geom.GeneralPath;
import java.awt.event.*;

import org.jetbrains.annotations.Nullable;

public class TabsWithActions extends JComponent implements PropertyChangeListener {

  private ActionManager myActionManager;
  private List<TabInfo> myInfos = new ArrayList<TabInfo>();

  private TabInfo mySelectedInfo;
  private Map<TabInfo, TabLabel> myInfo2Label = new HashMap<TabInfo, TabLabel>();
  private Map<TabInfo, JComponent> myInfo2Toolbar = new HashMap<TabInfo, JComponent>();
  private Dimension myHeaderFitSize;
  private Rectangle mySelectedBounds;

  private static final int INNER = 1;

  private List<MouseListener> myTabMouseListeners = new ArrayList<MouseListener>();
  private List<TabsListener> myTabListeners = new ArrayList<TabsListener>();
  private boolean myFocused;


  private ActionGroup myPopupGroup;
  private String myPopupPlace;
  private TabInfo myPopupInfo;
  private DefaultActionGroup myOwnGroup;

  public TabsWithActions(ActionManager actionManager, Disposable parent) {
    myActionManager = actionManager;

    myOwnGroup = new DefaultActionGroup();
    myOwnGroup.add(new SelectNextAction());
    myOwnGroup.add(new SelectPreviousAction());

    UIUtil.addAwtListener(new AWTEventListener() {
      public void eventDispatched(final AWTEvent event) {
        final FocusEvent fe = (FocusEvent)event;
        final TabsWithActions tabs = findTabs(fe.getComponent());
        if (tabs == null) return;
        if (fe.getID() == FocusEvent.FOCUS_LOST) {
          tabs.setFocused(false);
        } else if (fe.getID() == FocusEvent.FOCUS_GAINED) {
          tabs.setFocused(true);
        }
      }
    }, FocusEvent.FOCUS_EVENT_MASK, parent);

  }

  private TabsWithActions findTabs(Component c) {
    Component eachParent = c;
    while(eachParent != null) {
      if (eachParent instanceof TabsWithActions) {
        return (TabsWithActions)eachParent;
      }
      eachParent = eachParent.getParent();
    }

    return null;
  }


  public TabInfo addTab(TabInfo info, int index) {
    info.getChangeSupport().addPropertyChangeListener(this);
    add(info.getComponent());
    final TabLabel label = new TabLabel(info);
    myInfo2Label.put(info, label);

    if (index < 0) {
      myInfos.add(0, info);
    } else if (index > myInfos.size() - 1) {
      myInfos.add(info);
    } else {
      myInfos.add(index, info);
    }

    add(label);

    updateAll();

    return info;
  }
  public TabInfo addTab(TabInfo info) {
    return addTab(info, -1);
  }

  public ActionGroup getPopupGroup() {
    return myPopupGroup;
  }

  public String getPopupPlace() {
    return myPopupPlace;
  }

  public void setPopupGroup(final ActionGroup popupGroup, String place) {
    myPopupGroup = popupGroup;
    myPopupPlace = place;
  }

  private void updateAll() {
    update();
    updateListeners();
    updateSelected();
  }

  private void updateSelected() {
    setSelected(getSelectedInfo(), false);
  }

  public void setSelected(final TabInfo info, final boolean requestFocus) {
    TabInfo oldInfo = mySelectedInfo;
    mySelectedInfo = info;
    final TabInfo newInfo = getSelectedInfo();

    update();

    if (oldInfo != newInfo) {
      for (TabsListener eachListener : myTabListeners) {
        eachListener.selectionChanged(oldInfo, newInfo);
      }
    }

    if (requestFocus) {
      newInfo.getPreferredFocusableComponent().requestFocus();
    }
  }

  public void propertyChange(final PropertyChangeEvent evt) {
    final TabInfo tabInfo = (TabInfo)evt.getSource();
    if (TabInfo.ACTION_GROUP.equals(evt.getPropertyName())) {
      final JComponent old = myInfo2Toolbar.get(tabInfo);
      if (old != null) {
        remove(old);
      }
      final JComponent toolbar = createToolbarComponent(tabInfo);
      if (toolbar != null) {
        myInfo2Toolbar.put(tabInfo, toolbar);
        add(toolbar);
      }
    } else if (TabInfo.TEXT.equals(evt.getPropertyName())) {
      myInfo2Label.get(tabInfo).setText(tabInfo.getText());
    } else if (TabInfo.ICON.equals(evt.getPropertyName())) {
      myInfo2Label.get(tabInfo).setIcon(tabInfo.getIcon());
    }

    update();
  }

  @Nullable
  public TabInfo getSelectedInfo() {
    if (!myInfos.contains(mySelectedInfo)) {
      mySelectedInfo = null;
    }
    return mySelectedInfo != null ? mySelectedInfo : (myInfos.size() > 0 ? myInfos.get(0) : null);
  }

  protected JComponent createToolbarComponent(final TabInfo tabInfo) {
    if (tabInfo.getGroup() == null) return null;
    return myActionManager.createActionToolbar(tabInfo.getPlace(), tabInfo.getGroup(), true).getComponent();
  }

  public void doLayout() {
    final TabsWithActions.Max max = computeMaxSize();
    myHeaderFitSize = new Dimension(getSize().width, Math.max(max.myLabel.height, max.myToolbar.height));
    Insets insets = getInsets();
    if (insets == null) {
      insets = new Insets(0, 0, 0, 0);
    }
    int currentX = insets.left;
    final TabInfo selected = getSelectedInfo();
    mySelectedBounds = null;
    for (TabInfo eachInfo : myInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      final Dimension eachSize = label.getPreferredSize();
      label.setBounds(currentX, insets.top, eachSize.width, myHeaderFitSize.height);
      currentX += eachSize.width;

      final JComponent comp = eachInfo.getComponent();
      if (selected == eachInfo) {
        comp.setBounds(insets.left + INNER,
                       myHeaderFitSize.height + insets.top,
                       getWidth() - insets.left - insets.right - INNER * 2,
                       getHeight() - insets.top - insets.bottom - myHeaderFitSize.height - 1);
        mySelectedBounds = label.getBounds();
      } else {
        comp.setBounds(0, 0, 0, 0);
      }
      final JComponent eachToolbar = myInfo2Toolbar.get(eachInfo);
      if (eachToolbar != null) {
        eachToolbar.setBounds(0, 0, 0, 0);
      }
    }

    final JComponent selectedToolbar = myInfo2Toolbar.get(selected);
    if (selectedToolbar != null) {
      final int toolbarInset = getArcSize() * 2;
      if (currentX + selectedToolbar.getMinimumSize().width + toolbarInset < getWidth()) {
        selectedToolbar.setBounds(currentX + toolbarInset,
                                  insets.top,
                                  getSize().width - currentX - insets.left - toolbarInset,
                                  myHeaderFitSize.height - 1);
      } else {
        selectedToolbar.setBounds(0, 0, 0, 0);
      }
    }
  }

  private int getArcSize() {
    return 4;
  }

  protected void paintComponent(final Graphics g) {
    super.paintComponent(g);
    if (mySelectedBounds == null) return;

    final GraphicsConfig config = new GraphicsConfig(g);
    config.setAntialiasing(true);

    Graphics2D g2d = (Graphics2D)g;
    final GeneralPath path = new GeneralPath();
    Insets insets = getInsets();
    if (insets == null) {
      insets = new Insets(0, 0, 0, 0);
    }
    final int bottomY = myHeaderFitSize.height + insets.top - 1;
    final int topY = insets.top;
    int leftX = mySelectedBounds.x;
    int rightX = mySelectedBounds.x + mySelectedBounds.width;
    int arc = getArcSize();

    path.moveTo(insets.left, bottomY);
    path.lineTo(leftX, bottomY);
    path.lineTo(leftX, topY + arc);
    path.quadTo(leftX, topY, leftX + arc, topY);
    path.lineTo(rightX - arc, topY);
    path.quadTo(rightX, topY, rightX, topY + arc);
    path.lineTo(rightX, bottomY - arc);
    path.quadTo(rightX, bottomY, rightX + arc, bottomY);
    path.lineTo(getWidth() - insets.right, bottomY);
    path.closePath();

    final Color from;
    final Color to;
    final int alpha ;
    if (myFocused) {
      alpha = 100;
      from = toAlpha(UIUtil.getListSelectionBackground(), alpha);
      to = toAlpha(UIUtil.getListSelectionBackground(), alpha);
    } else {
      alpha = 150;
      from = toAlpha(UIUtil.getPanelBackgound().brighter(), alpha);
      to = toAlpha(UIUtil.getPanelBackgound(), alpha);
    }

    g2d.setPaint(new GradientPaint(mySelectedBounds.x, topY, from, mySelectedBounds.x, bottomY, to));
    g2d.fill(path);
    if (myFocused) {
      g2d.setColor(UIUtil.getListSelectionBackground().darker().darker());      
    } else {
      g2d.setColor(CaptionPanel.CNT_ACTIVE_COLOR.darker());
    }
    g2d.draw(path);

    g2d.drawRect(insets.left, bottomY, getWidth() - insets.left - insets.right - 1, getHeight() - bottomY - insets.bottom - 1);

    config.restore();
  }

  private Color toAlpha(final Color color, final int alpha) {
    return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
  }

  private Max computeMaxSize() {
    Max max = new Max();
    for (TabInfo eachInfo : myInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      max.myLabel.height = Math.max(max.myLabel.height, label.getPreferredSize().height);
      max.myLabel.width = Math.max(max.myLabel.width, label.getPreferredSize().width);
      final JComponent toolbar = myInfo2Toolbar.get(eachInfo);
      if (toolbar != null) {
        max.myToolbar.height = Math.max(max.myToolbar.height, toolbar.getPreferredSize().height);
        max.myToolbar.width = Math.max(max.myToolbar.width, toolbar.getPreferredSize().width);
      }
    }

    max.myToolbar.height++;
    
    return max;
  }

  public int getTabCount() {
    return myInfos.size();
  }

  public void removeTab(final JComponent component) {
    removeTab(findInfo(component));
  }

  public void removeTab(TabInfo info) {
    if (info == null) return;

    remove(myInfo2Label.get(info));
    final JComponent tb = myInfo2Toolbar.get(info);
    if (tb != null) {
      remove(tb);
    }
    remove(info.getComponent());

    myInfos.remove(info);
    myInfo2Label.remove(info);
    myInfo2Toolbar.remove(info);

    updateAll();
  }

  public TabInfo findInfo(Component component) {
    for (TabInfo each : myInfos) {
      if (each.getComponent() == component) return each;
    }

    return null;
  }

  public TabInfo findInfo(String text) {
    if (text == null) return null;

    for (TabInfo each : myInfos) {
      if (text.equals(each.getText())) return each;
    }

    return null;
  }

  public TabInfo findInfo(MouseEvent event) {
    final Point point = SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), this);
    return _findInfo(point, false);
  }

  public TabInfo findTabLabelBy(final Point point) {
    return _findInfo(point, true);
  }

  private TabInfo _findInfo(final Point point, boolean labelsOnly) {
    Component component = findComponentAt(point);
    if (component == null) return null;
    while (component != this || component != null) {
      if (component instanceof TabLabel) {
        return ((TabLabel)component).getInfo();
      } else if (!labelsOnly) {
        final TabInfo info = findInfo(component);
        if (info != null) return info;
      }
      if (component == null) break;
      component = component.getParent();
    }

    return null;
  }

  public void removeAllTabs() {
    final TabInfo[] infos = myInfos.toArray(new TabInfo[myInfos.size()]);
    for (TabInfo each : infos) {
      removeTab(each);
    }
  }

  private class Max {
    Dimension myLabel = new Dimension();
    Dimension myToolbar = new Dimension();
  }

  private void update() {
    revalidate();
    repaint();
  }

  ActionManager getActionManager() {
    return myActionManager;
  }

   public void addTabMouseListener(MouseListener listener) {
    removeListeners();
    myTabMouseListeners.add(listener);
    addListeners();
  }

  public void removeTabMouseListener(MouseListener listener) {
    removeListeners();
    myTabMouseListeners.remove(listener);
    addListeners();
  }

  private void addListeners() {
    for (TabInfo eachInfo : myInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      for (MouseListener eachListener : myTabMouseListeners) {
        label.addMouseListener(eachListener);
      }
    }
  }

  private void removeListeners() {
    for (TabInfo eachInfo : myInfos) {
      final TabLabel label = myInfo2Label.get(eachInfo);
      for (MouseListener eachListener : myTabMouseListeners) {
        label.removeMouseListener(eachListener);
      }
    }
  }

  private void updateListeners() {
    removeListeners();
    addListeners();
  }

  public void addListener(TabsListener listener) {
    myTabListeners.add(listener);
  }

  private class TabLabel extends JPanel {
    private JLabel myLabel = new JLabel();
    private TabInfo myInfo;

    public TabLabel(final TabInfo info) {
      myInfo = info;
      setOpaque(false);
      setLayout(new BorderLayout());
      add(myLabel, BorderLayout.CENTER);

      addMouseListener(new MouseAdapter() {
        public void mousePressed(final MouseEvent e) {
          if (e.getClickCount() == 1 && e.getButton() == MouseEvent.BUTTON1 && !e.isPopupTrigger()) {
            setSelected(info, true);
          } else if (e.getClickCount() == 1 && e.isPopupTrigger()) {
            String place = getPopupPlace();
            place = place != null ? place : ActionPlaces.UNKNOWN;
            myPopupInfo = myInfo;

            final DefaultActionGroup toShow = new DefaultActionGroup();
            if (getPopupGroup() != null) {
              toShow.addAll(getPopupGroup());
              toShow.addSeparator();
            }
            toShow.addAll(myOwnGroup);

            myActionManager.createActionPopupMenu(place, toShow).getComponent().show(e.getComponent(), e.getX(), e.getY());
            onPopup(myPopupInfo);
          }
        }
      });
      setBorder(new EmptyBorder(4, 8, 4, 8));
    }

    public void setText(final String text) {
      myLabel.setText(text);
    }

    public void setIcon(final Icon icon) {
      myLabel.setIcon(icon);
    }

    public TabInfo getInfo() {
      return myInfo;
    }
  }

  protected void onPopup(final TabInfo popupInfo) {
  }

  public void setFocused(final boolean focused) {
    myFocused = focused;
    repaint();
  }

  private abstract class BaseAction extends AnAction {

    private ShadowAction myShadow;

    protected BaseAction(final String copyFromID) {
      myShadow = new ShadowAction(this, myActionManager.getAction(copyFromID), TabsWithActions.this);
    }

    public final void update(final AnActionEvent e) {
      final boolean visible = myInfos.size() > 0;
      e.getPresentation().setVisible(visible);
      if (!visible) return;

      final int selectedIndex = myInfos.indexOf(getSelectedInfo());
      final boolean enabled = myInfos.size() > 0 && selectedIndex >= 0;
      e.getPresentation().setEnabled(enabled);
      if (enabled) {
        _update(e, selectedIndex);
      }
    }

    protected abstract void _update(AnActionEvent e, int selectedIndex);

    public final void actionPerformed(final AnActionEvent e) {
      _actionPerformed(e, myInfos.indexOf(getSelectedInfo()));
    }

    protected abstract void _actionPerformed(final AnActionEvent e, final int selectedIndex);
  }

  private class SelectNextAction extends BaseAction {

    public SelectNextAction() {
      super(IdeActions.ACTION_NEXT_TAB);
    }

    protected void _update(final AnActionEvent e, int selectedIndex) {
      e.getPresentation().setEnabled(myInfos.size() > 0 && selectedIndex < myInfos.size() - 1);
    }

    protected void _actionPerformed(final AnActionEvent e, final int selectedIndex) {
      setSelected(myInfos.get(selectedIndex + 1), true);
    }
  }

  private class SelectPreviousAction extends BaseAction {
    public SelectPreviousAction() {
      super(IdeActions.ACTION_PREVIOUS_TAB);
    }

    protected void _update(final AnActionEvent e, int selectedIndex) {
      e.getPresentation().setEnabled(myInfos.size() > 0 && selectedIndex > 0);
    }

    protected void _actionPerformed(final AnActionEvent e, final int selectedIndex) {
      setSelected(myInfos.get(selectedIndex - 1), true);
    }
  }

  public static void main(String[] args) {
    final JFrame frame = new JFrame();
    frame.getContentPane().setLayout(new BorderLayout());
    final int[] count = new int[1];
    final TabsWithActions tabs = new TabsWithActions(null, new Disposable() {
      public void dispose() {
      }
    }) {
      protected JComponent createToolbarComponent(final TabInfo tabInfo) {
        final JLabel jLabel = new JLabel("X" + (++count[0]));
        jLabel.setBorder(new LineBorder(Color.red));
        return jLabel;
      }
    };
    frame.getContentPane().add(tabs, BorderLayout.CENTER);
    final JCheckBox f = new JCheckBox("Focused");
    f.addItemListener(new ItemListener() {
      public void itemStateChanged(final ItemEvent e) {
        tabs.setFocused(f.isSelected());
      }
    });
    frame.getContentPane().add(f, BorderLayout.SOUTH);

    tabs.addListener(new TabsListener() {
      public void selectionChanged(final TabInfo oldSelection, final TabInfo newSelection) {
        System.out.println("TabsWithActions.selectionChanged old=" + oldSelection + " new=" + newSelection);
      }
    });

    tabs.addTab(new TabInfo(new JTree())).setText("Tree").setActions(new DefaultActionGroup(), null).setIcon(IconLoader.getIcon("/debugger/frame.png"));
    tabs.addTab(new TabInfo(new JTree())).setText("Tree2");
    tabs.addTab(new TabInfo(new JTable())).setText("Table").setActions(new DefaultActionGroup(), null);

    tabs.setBorder(new EmptyBorder(6, 6, 6, 6));

    frame.setBounds(200, 200, 300, 200);
    frame.show();
  }


}
