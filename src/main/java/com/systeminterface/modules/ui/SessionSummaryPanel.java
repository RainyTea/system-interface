package com.systeminterface.modules.ui;

import com.systeminterface.services.state.SessionTotals;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalDate;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.DynamicGridLayout;

/**
 * Side-panel "Session Summary" block: Today and All-time totals, each with
 * Rewards / Costs / Net rows. Costs is always 0 and Net always equals Rewards —
 * this plugin doesn't track spend, only reward value accrued (see {@link SessionTotals}).
 * Right-click either block to reset it. The coordinator ({@code SystemInterfacePanel}) wires the
 * reset callbacks and calls {@link #update(long)} with the authoritative all-time value whenever
 * totals change.
 */
public class SessionSummaryPanel extends JPanel
{
	private static final Color ACCENT = new Color(120, 200, 255);

	private final SessionTotals totals;
	private final Runnable onResetToday;
	private final Runnable onResetAllTime;

	private final JLabel todayLabel = new JLabel(" ", SwingConstants.CENTER);
	private final JLabel allTimeLabel = new JLabel(" ", SwingConstants.CENTER);

	/** Last all-time value passed to {@link #update(long)}, kept so a today-only reset can redraw without new input. */
	private long lastAllTimeRewards;

	public SessionSummaryPanel(SessionTotals totals, Runnable onResetToday, Runnable onResetAllTime)
	{
		super();
		this.totals = totals;
		this.onResetToday = onResetToday;
		this.onResetAllTime = onResetAllTime;

		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Session Summary");
		title.setForeground(ACCENT);
		title.setBorder(new EmptyBorder(6, 6, 6, 0));
		add(title);

		final JPanel body = new JPanel();
		body.setLayout(new DynamicGridLayout(0, 1, 0, 2));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.setBorder(new EmptyBorder(4, 4, 4, 4));

		todayLabel.setForeground(ACCENT);
		todayLabel.setBorder(new EmptyBorder(2, 0, 0, 0));
		todayLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		attachResetMenu(todayLabel, "Reset today", this::resetToday);
		body.add(todayLabel);

		allTimeLabel.setForeground(ACCENT);
		allTimeLabel.setBorder(new EmptyBorder(6, 0, 0, 0));
		allTimeLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		attachResetMenu(allTimeLabel, "Reset all-time", this::confirmResetAllTime);
		body.add(allTimeLabel);

		add(body);

		update(0);
	}

	/**
	 * Rebuilds the value labels. Today = {@code totals.todayRewards(LocalDate.now())};
	 * All-time = {@code allTimeRewards}. Costs is always 0 and Net always equals Rewards
	 * for both blocks.
	 */
	public void update(long allTimeRewards)
	{
		this.lastAllTimeRewards = allTimeRewards;

		final long todayRewards = totals.todayRewards(LocalDate.now());
		todayLabel.setText(blockText("Today", todayRewards));
		allTimeLabel.setText(blockText("All-time", allTimeRewards));
	}

	private static String blockText(String title, long rewards)
	{
		return "<html><div style='text-align:center;'>"
			+ "<b>" + title + "</b><br>"
			+ "Rewards: " + PanelFormat.gp(rewards) + "<br>"
			+ "Costs: " + PanelFormat.gp(0) + "<br>"
			+ "Net: " + PanelFormat.gp(rewards)
			+ "</div></html>";
	}

	private void resetToday()
	{
		onResetToday.run();
		// The coordinator will call update(...) with the authoritative all-time value; this
		// redraw with the last-known all-time keeps the Today block from looking stale meanwhile.
		update(lastAllTimeRewards);
	}

	private void confirmResetAllTime()
	{
		final int choice = JOptionPane.showConfirmDialog(this,
			"Reset all-time totals? This zeroes your all-time combat and skilling totals and today's bucket, and cannot be undone.",
			"Reset all-time", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
		if (choice == JOptionPane.OK_OPTION)
		{
			onResetAllTime.run();
		}
	}

	private void attachResetMenu(JLabel label, String menuText, Runnable action)
	{
		final JPopupMenu menu = new JPopupMenu();
		final javax.swing.JMenuItem item = new javax.swing.JMenuItem(menuText);
		item.addActionListener(e -> action.run());
		menu.add(item);

		label.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e)
			{
				maybeShow(e);
			}

			@Override
			public void mouseReleased(MouseEvent e)
			{
				maybeShow(e);
			}

			private void maybeShow(MouseEvent e)
			{
				if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
				{
					menu.show(label, e.getX(), e.getY());
				}
			}
		});
	}
}
