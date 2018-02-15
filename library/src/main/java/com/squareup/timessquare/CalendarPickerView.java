// Copyright 2012 Square, Inc.
package com.squareup.timessquare;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.Toast;

import java.text.DateFormat;
import java.text.DateFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Formatter;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import static java.util.Calendar.DATE;
import static java.util.Calendar.DAY_OF_MONTH;
import static java.util.Calendar.DAY_OF_WEEK;
import static java.util.Calendar.HOUR_OF_DAY;
import static java.util.Calendar.MILLISECOND;
import static java.util.Calendar.MINUTE;
import static java.util.Calendar.MONTH;
import static java.util.Calendar.SECOND;
import static java.util.Calendar.YEAR;

/**
 * Android component to allow picking a date from a calendar view (a list of months).  Must be
 * initialized after inflation with {@link #init(Date, Date)} and can be customized with any of the
 * {@link FluentInitializer} methods returned.  The currently selected date can be retrieved with
 * {@link #getSelectedDate()}.
 */
public class CalendarPickerView extends RecyclerView {


  public OnMonthSelectedListener getMonthSelectedListener() {
    return monthSelectedListener;
  }

  public void setMonthSelectedListener(OnMonthSelectedListener monthSelectedListener) {
    this.monthSelectedListener = monthSelectedListener;
  }

  public enum SelectionMode {
    /**
     * Only one date will be selectable.  If there is already a selected date and you select a new
     * one, the old date will be unselected.
     */
    SINGLE,
    /** Multiple dates will be selectable.  Selecting an already-selected date will un-select it. */
    MULTIPLE,
    /**
     * Allows you to select a date range.  Previous selections are cleared when you either:
     * <ul>
     * <li>Have a range selected and select another date (even if it's in the current range).</li>
     * <li>Have one date selected and then select an earlier date.</li>
     * </ul>
     */
    RANGE,
    /**
     * Allows you to select multiple ranges
     */
    MULTI_RANGE;
  }
  // List of languages that require manually creation of YYYY MMMM date format
  private static final ArrayList<String> explicitlyNumericYearLocaleLanguages =
      new ArrayList<>(Arrays.asList("ar", "my"));

  private final CalendarPickerView.MonthAdapter adapter;

  private final IndexedLinkedHashMap<String, List<List<MonthCellDescriptor>>> cells =
      new IndexedLinkedHashMap<>();
  final MonthView.Listener listener = new CellClickedListener();
  final List<MonthDescriptor> months = new ArrayList<>();
  final List<MonthCellDescriptor> selectedCells = new ArrayList<>();
  final List<MonthCellDescriptor> highlightedCells = new ArrayList<>();
  final List<Calendar> selectedCals = new ArrayList<>();
  final List<Calendar> highlightedCals = new ArrayList<>();
  private Locale locale;
  private TimeZone timeZone;
  private DateFormat weekdayNameFormat;
  private DateFormat fullDateFormat;
  private Calendar minCal;
  private Calendar maxCal;
  private Calendar monthCounter;
  private boolean displayOnly;
  SelectionMode selectionMode;
  Calendar today;
  private boolean shouldSnapToMonth;
  private int dividerColor;
  private int dayBackgroundResId;
  private int dayTextColorResId;
  private int titleTextStyle;
  private boolean displayHeader;
  private int headerTextColor;
  private boolean displayDayNamesHeaderRow;
  private boolean displayAlwaysDigitNumbers;
  private Typeface titleTypeface;
  private Typeface dateTypeface;

  private OnMonthSelectedListener monthSelectedListener;
  private OnDateSelectedListener dateListener;
  private DateSelectableFilter dateConfiguredListener;
  private OnInvalidDateSelectedListener invalidDateListener =
      new DefaultOnInvalidDateSelectedListener();
  private CellClickInterceptor cellClickInterceptor;
  private List<CalendarCellDecorator> decorators;
  private DayViewAdapter dayViewAdapter = new DefaultDayViewAdapter();

  private boolean monthsReverseOrder;

  private final StringBuilder monthBuilder = new StringBuilder(50);
  private Formatter monthFormatter;

  public void setDecorators(List<CalendarCellDecorator> decorators) {
    this.decorators = decorators;
    if (null != adapter) {
      adapter.notifyDataSetChanged();
    }
  }

  public List<CalendarCellDecorator> getDecorators() {
    return decorators;
  }

  public CalendarPickerView(Context context, AttributeSet attrs) {
    super(context, attrs);

    Resources res = context.getResources();
    TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CalendarPickerView);
    final int bg = a.getColor(R.styleable.CalendarPickerView_android_background,
        res.getColor(R.color.calendar_bg));
    dividerColor = a.getColor(R.styleable.CalendarPickerView_tsquare_dividerColor,
        res.getColor(R.color.calendar_divider));
    dayBackgroundResId = a.getResourceId(R.styleable.CalendarPickerView_tsquare_dayBackground,
        R.drawable.calendar_bg_selector);
    dayTextColorResId = a.getResourceId(R.styleable.CalendarPickerView_tsquare_dayTextColor,
        R.color.calendar_text_selector);
    titleTextStyle = a.getResourceId(R.styleable.CalendarPickerView_tsquare_titleTextStyle,
        R.style.CalendarTitle);
    displayHeader = a.getBoolean(R.styleable.CalendarPickerView_tsquare_displayHeader, true);
    headerTextColor = a.getColor(R.styleable.CalendarPickerView_tsquare_headerTextColor,
        res.getColor(R.color.calendar_text_active));
    displayDayNamesHeaderRow =
            a.getBoolean(R.styleable.CalendarPickerView_tsquare_displayDayNamesHeaderRow, true);
    displayAlwaysDigitNumbers =
            a.getBoolean(R.styleable.CalendarPickerView_tsquare_displayAlwaysDigitNumbers, false);
    shouldSnapToMonth = a.getBoolean(R.styleable.CalendarPickerView_tsquare_snapToMonth, false);

    a.recycle();

    adapter = new MonthAdapter();
    setLayoutManager(new LinearLayoutManager(getContext(), LinearLayoutManager.VERTICAL, false));
    setBackgroundColor(bg);

    timeZone = TimeZone.getDefault();
    locale = Locale.getDefault();
    today = Calendar.getInstance(timeZone, locale);
    minCal = Calendar.getInstance(timeZone, locale);
    maxCal = Calendar.getInstance(timeZone, locale);
    monthCounter = Calendar.getInstance(timeZone, locale);
    weekdayNameFormat = new SimpleDateFormat(context.getString(R.string.day_name_format), locale);
    weekdayNameFormat.setTimeZone(timeZone);
    fullDateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale);
    fullDateFormat.setTimeZone(timeZone);

    if (isInEditMode()) {
      Calendar nextYear = Calendar.getInstance(timeZone, locale);
      nextYear.add(Calendar.YEAR, 1);

      init(new Date(), nextYear.getTime()) //
          .withSelectedDate(new Date());
    }

    if (shouldSnapToMonth) {
      this.setOnScrollListener(new OnScrollListener() {
        private boolean wasScrolling;

        @Override
        public void onScrollStateChanged(RecyclerView view, int scrollState) {
          LinearLayoutManager linearLayoutManager = (LinearLayoutManager) view.getLayoutManager();
          switch (scrollState) {
            case RecyclerView.SCROLL_STATE_IDLE:
              if (wasScrolling) {
                // get first visible item
                View itemView = view.getChildAt(0);
                int top = Math.abs(itemView.getTop()); // top is a negative value
                int bottom = Math.abs(itemView.getBottom());

                int item = linearLayoutManager.findFirstVisibleItemPosition();
                if (top >= bottom) {
                  item += 1;
                }
                view.scrollToPosition(item);

                if (monthSelectedListener != null) {
                  MonthDescriptor month = months.get(item);
                  monthSelectedListener.onMonthSelected(month.getMonth(), month.getYear());
                }
              }
              wasScrolling = false;
              break;
            case RecyclerView.SCROLL_STATE_DRAGGING:
            case RecyclerView.SCROLL_STATE_SETTLING:
              wasScrolling = true;
              break;
          }
        }
      });
    }
  }

  /**
   * Both date parameters must be non-null and their {@link Date#getTime()} must not return 0. Time
   * of day will be ignored.  For instance, if you pass in {@code minDate} as 11/16/2012 5:15pm and
   * {@code maxDate} as 11/16/2013 4:30am, 11/16/2012 will be the first selectable date and
   * 11/15/2013 will be the last selectable date ({@code maxDate} is exclusive).
   * <p>
   * This will implicitly set the {@link SelectionMode} to {@link SelectionMode#SINGLE}.  If you
   * want a different selection mode, use {@link FluentInitializer#inMode(SelectionMode)} on the
   * {@link FluentInitializer} this method returns.
   * <p>
   * The calendar will be constructed using the given time zone and the given locale. This means
   * that all dates will be in given time zone, all names (months, days) will be in the language
   * of the locale and the weeks start with the day specified by the locale.
   *
   * @param minDate Earliest selectable date, inclusive.  Must be earlier than {@code maxDate}.
   * @param maxDate Latest selectable date, exclusive.  Must be later than {@code minDate}.
   */
  public FluentInitializer init(Date minDate, Date maxDate, TimeZone timeZone, Locale locale) {
    if (minDate == null || maxDate == null) {
      throw new IllegalArgumentException(
          "minDate and maxDate must be non-null.  " + dbg(minDate, maxDate));
    }
    if (minDate.after(maxDate)) {
      throw new IllegalArgumentException(
          "minDate must be before maxDate.  " + dbg(minDate, maxDate));
    }
    if (locale == null) {
      throw new IllegalArgumentException("Locale is null.");
    }
    if (timeZone == null) {
      throw new IllegalArgumentException("Time zone is null.");
    }

    // Make sure that all calendar instances use the same time zone and locale.
    this.timeZone = timeZone;
    this.locale = locale;
    today = Calendar.getInstance(timeZone, locale);
    minCal = Calendar.getInstance(timeZone, locale);
    maxCal = Calendar.getInstance(timeZone, locale);
    monthCounter = Calendar.getInstance(timeZone, locale);
    for (MonthDescriptor month : months) {
      month.setLabel(formatMonthDate(month.getDate()));
    }
    weekdayNameFormat =
        new SimpleDateFormat(getContext().getString(R.string.day_name_format), locale);
    weekdayNameFormat.setTimeZone(timeZone);
    fullDateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM, locale);
    fullDateFormat.setTimeZone(timeZone);
    monthFormatter = new Formatter(monthBuilder, locale);

    this.selectionMode = SelectionMode.SINGLE;
    // Clear out any previously-selected dates/cells.
    selectedCals.clear();
    selectedCells.clear();
    highlightedCals.clear();
    highlightedCells.clear();

    // Clear previous state.
    cells.clear();
    months.clear();
    minCal.setTime(minDate);
    maxCal.setTime(maxDate);
    setMidnight(minCal);
    setMidnight(maxCal);
    displayOnly = false;

    // maxDate is exclusive: bump back to the previous day so if maxDate is the first of a month,
    // we don't accidentally include that month in the view.
    maxCal.add(MINUTE, -1);

    // Now iterate between minCal and maxCal and build up our list of months to show.
    monthCounter.setTime(minCal.getTime());
    final int maxMonth = maxCal.get(MONTH);
    final int maxYear = maxCal.get(YEAR);
    while ((monthCounter.get(MONTH) <= maxMonth // Up to, including the month.
        || monthCounter.get(YEAR) < maxYear) // Up to the year.
        && monthCounter.get(YEAR) < maxYear + 1) { // But not > next yr.
      Date date = monthCounter.getTime();
      MonthDescriptor month =
          new MonthDescriptor(monthCounter.get(MONTH), monthCounter.get(YEAR),
                  date, formatMonthDate(date));
      cells.put(monthKey(month), getMonthCells(month, monthCounter));
      Logr.d("Adding month %s", month);
      months.add(month);
      monthCounter.add(MONTH, 1);
    }

    validateAndUpdate();
    return new FluentInitializer();
  }

  /**
   * Both date parameters must be non-null and their {@link Date#getTime()} must not return 0. Time
   * of day will be ignored.  For instance, if you pass in {@code minDate} as 11/16/2012 5:15pm and
   * {@code maxDate} as 11/16/2013 4:30am, 11/16/2012 will be the first selectable date and
   * 11/15/2013 will be the last selectable date ({@code maxDate} is exclusive).
   * <p>
   * This will implicitly set the {@link SelectionMode} to {@link SelectionMode#SINGLE}.  If you
   * want a different selection mode, use {@link FluentInitializer#inMode(SelectionMode)} on the
   * {@link FluentInitializer} this method returns.
   * <p>
   * The calendar will be constructed using the default locale as returned by
   * {@link java.util.Locale#getDefault()} and default time zone as returned by
   * {@link java.util.TimeZone#getDefault()}. If you wish the calendar to be constructed using a
   * different locale or time zone, use
   * {@link #init(java.util.Date, java.util.Date, java.util.Locale)},
   * {@link #init(java.util.Date, java.util.Date, java.util.TimeZone)} or
   * {@link #init(java.util.Date, java.util.Date, java.util.TimeZone, java.util.Locale)}.
   *
   * @param minDate Earliest selectable date, inclusive.  Must be earlier than {@code maxDate}.
   * @param maxDate Latest selectable date, exclusive.  Must be later than {@code minDate}.
   */
  public FluentInitializer init(Date minDate, Date maxDate) {
    return init(minDate, maxDate, TimeZone.getDefault(), Locale.getDefault());
  }

  /**
   * Both date parameters must be non-null and their {@link Date#getTime()} must not return 0. Time
   * of day will be ignored.  For instance, if you pass in {@code minDate} as 11/16/2012 5:15pm and
   * {@code maxDate} as 11/16/2013 4:30am, 11/16/2012 will be the first selectable date and
   * 11/15/2013 will be the last selectable date ({@code maxDate} is exclusive).
   * <p>
   * This will implicitly set the {@link SelectionMode} to {@link SelectionMode#SINGLE}.  If you
   * want a different selection mode, use {@link FluentInitializer#inMode(SelectionMode)} on the
   * {@link FluentInitializer} this method returns.
   * <p>
   * The calendar will be constructed using the given time zone and the default locale as returned
   * by {@link java.util.Locale#getDefault()}. This means that all dates will be in given time zone.
   * If you wish the calendar to be constructed using a different locale, use
   * {@link #init(java.util.Date, java.util.Date, java.util.Locale)} or
   * {@link #init(java.util.Date, java.util.Date, java.util.TimeZone, java.util.Locale)}.
   *
   * @param minDate Earliest selectable date, inclusive.  Must be earlier than {@code maxDate}.
   * @param maxDate Latest selectable date, exclusive.  Must be later than {@code minDate}.
   */
  public FluentInitializer init(Date minDate, Date maxDate, TimeZone timeZone) {
    return init(minDate, maxDate, timeZone, Locale.getDefault());
  }

  /**
   * Both date parameters must be non-null and their {@link Date#getTime()} must not return 0. Time
   * of day will be ignored.  For instance, if you pass in {@code minDate} as 11/16/2012 5:15pm and
   * {@code maxDate} as 11/16/2013 4:30am, 11/16/2012 will be the first selectable date and
   * 11/15/2013 will be the last selectable date ({@code maxDate} is exclusive).
   * <p>
   * This will implicitly set the {@link SelectionMode} to {@link SelectionMode#SINGLE}.  If you
   * want a different selection mode, use {@link FluentInitializer#inMode(SelectionMode)} on the
   * {@link FluentInitializer} this method returns.
   * <p>
   * The calendar will be constructed using the given locale. This means that all names
   * (months, days) will be in the language of the locale and the weeks start with the day
   * specified by the locale.
   * <p>
   * The calendar will be constructed using the given locale and the default time zone as returned
   * by {@link java.util.TimeZone#getDefault()}. This means that all names (months, days) will be
   * in the language of the locale and the weeks start with the day specified by the locale.
   * If you wish the calendar to be constructed using a different time zone, use
   * {@link #init(java.util.Date, java.util.Date, java.util.TimeZone)} or
   * {@link #init(java.util.Date, java.util.Date, java.util.TimeZone, java.util.Locale)}.
   *
   * @param minDate Earliest selectable date, inclusive.  Must be earlier than {@code maxDate}.
   * @param maxDate Latest selectable date, exclusive.  Must be later than {@code minDate}.
   */
  public FluentInitializer init(Date minDate, Date maxDate, Locale locale) {
    return init(minDate, maxDate, TimeZone.getDefault(), locale);
  }

  public class FluentInitializer {
    /** Override the {@link SelectionMode} from the default ({@link SelectionMode#SINGLE}). */
    public FluentInitializer inMode(SelectionMode mode) {
      selectionMode = mode;
      validateAndUpdate();
      return this;
    }

    /**
     * Set an initially-selected date.  The calendar will scroll to that date if it's not already
     * visible.
     */
    public FluentInitializer withSelectedDate(Date selectedDates) {
      return withSelectedDates(Collections.singletonList(selectedDates));
    }

    /**
     * Set multiple selected dates.  This will throw an {@link IllegalArgumentException} if you
     * pass in multiple dates and haven't already called {@link #inMode(SelectionMode)}.
     */
    public FluentInitializer withSelectedDates(Collection<Date> selectedDates) {
      if (selectionMode == SelectionMode.SINGLE && selectedDates.size() > 1) {
        throw new IllegalArgumentException("SINGLE mode can't be used with multiple selectedDates");
      }
      if (selectionMode == SelectionMode.RANGE && selectedDates.size() > 2) {
        throw new IllegalArgumentException(
            "RANGE mode only allows two selectedDates.  You tried to pass " + selectedDates.size());
      }
      if (selectedDates != null) {
        for (Date date : selectedDates) {
          selectDate(date);
        }
      }
      scrollToSelectedDates();

      validateAndUpdate();
      return this;
    }

    public FluentInitializer withHighlightedDates(Collection<Date> dates) {
      highlightDates(dates);
      return this;
    }

    public FluentInitializer withHighlightedDate(Date date) {
      return withHighlightedDates(Collections.singletonList(date));
    }

    @SuppressLint("SimpleDateFormat")
    public FluentInitializer setShortWeekdays(String[] newShortWeekdays) {
      DateFormatSymbols symbols = new DateFormatSymbols(locale);
      symbols.setShortWeekdays(newShortWeekdays);
      weekdayNameFormat =
          new SimpleDateFormat(getContext().getString(R.string.day_name_format), symbols);
      return this;
    }

    public FluentInitializer displayOnly() {
      displayOnly = true;
      return this;
    }

    public FluentInitializer withMonthsReverseOrder(boolean monthsRevOrder) {
      monthsReverseOrder = monthsRevOrder;
      return this;
    }

  }

  private void validateAndUpdate() {
    if (getAdapter() == null) {
      setAdapter(adapter);
    }
    adapter.notifyDataSetChanged();
  }

  private void scrollToSelectedMonth(final int selectedIndex) {
    scrollToSelectedMonth(selectedIndex, false);
  }

  private void scrollToSelectedMonth(final int selectedIndex, final boolean smoothScroll) {
    post(new Runnable() {
      @Override public void run() {
        Logr.d("Scrolling to position %d", selectedIndex);

        if (smoothScroll) {
          smoothScrollToPosition(selectedIndex);
        } else {
          scrollToPosition(selectedIndex);
        }
      }
    });
  }

  private void scrollToSelectedDates() {
    Integer selectedIndex = null;
    Integer todayIndex = null;
    Calendar today = Calendar.getInstance(timeZone, locale);
    for (int c = 0; c < months.size(); c++) {
      MonthDescriptor month = months.get(c);
      if (selectedIndex == null) {
        for (Calendar selectedCal : selectedCals) {
          if (sameMonth(selectedCal, month)) {
            selectedIndex = c;
            break;
          }
        }
        if (selectedIndex == null && todayIndex == null && sameMonth(today, month)) {
          todayIndex = c;
        }
      }
    }
    if (selectedIndex != null) {
      scrollToSelectedMonth(selectedIndex);
    } else if (todayIndex != null) {
      scrollToSelectedMonth(todayIndex);
    }
  }

  public boolean scrollToDate(Date date) {
    Integer selectedIndex = null;

    Calendar cal = Calendar.getInstance(timeZone, locale);
    cal.setTime(date);
    for (int c = 0; c < months.size(); c++) {
      MonthDescriptor month = months.get(c);
      if (sameMonth(cal, month)) {
        selectedIndex = c;
        break;
      }
    }
    if (selectedIndex != null) {
      scrollToSelectedMonth(selectedIndex);
      return true;
    }
    return false;
  }

  /**
   * This method should only be called if the calendar is contained in a dialog, and it should only
   * be called once, right after the dialog is shown (using
   * {@link android.content.DialogInterface.OnShowListener} or
   * {@link android.app.DialogFragment#onStart()}).
   */
  public void fixDialogDimens() {
    Logr.d("Fixing dimensions to h = %d / w = %d", getMeasuredHeight(), getMeasuredWidth());
    // Fix the layout height/width after the dialog has been shown.
    getLayoutParams().height = getMeasuredHeight();
    getLayoutParams().width = getMeasuredWidth();
    // Post this runnable so it runs _after_ the dimen changes have been applied/re-measured.
    post(new Runnable() {
      @Override public void run() {
        Logr.d("Dimens are fixed: now scroll to the selected date");
        scrollToSelectedDates();
      }
    });
  }

  /**
   * Set the typeface to be used for month titles.
   */
  public void setTitleTypeface(Typeface titleTypeface) {
    this.titleTypeface = titleTypeface;
    validateAndUpdate();
  }

  /**
   * Sets the typeface to be used within the date grid.
   */
  public void setDateTypeface(Typeface dateTypeface) {
    this.dateTypeface = dateTypeface;
    validateAndUpdate();
  }

  /**
   * Sets the typeface to be used for all text within this calendar.
   */
  public void setTypeface(Typeface typeface) {
    setTitleTypeface(typeface);
    setDateTypeface(typeface);
  }

  /**
   * This method should only be called if the calendar is contained in a dialog, and it should only
   * be called when the screen has been rotated and the dialog should be re-measured.
   */
  public void unfixDialogDimens() {
    Logr.d("Reset the fixed dimensions to allow for re-measurement");
    // Fix the layout height/width after the dialog has been shown.
    getLayoutParams().height = LayoutParams.MATCH_PARENT;
    getLayoutParams().width = LayoutParams.MATCH_PARENT;
    requestLayout();
  }

  @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    if (months.isEmpty()) {
      throw new IllegalStateException(
          "Must have at least one month to display.  Did you forget to call init()?");
    }
    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
  }

  public Date getSelectedDate() {
    return (selectedCals.size() > 0 ? selectedCals.get(0).getTime() : null);
  }

  public List<Calendar> getRanges() {
    return selectedCals;
  }

  public List<Date> getSelectedDates() {
    List<Date> selectedDates = new ArrayList<>();
    for (MonthCellDescriptor cal : selectedCells) {
      selectedDates.add(cal.getDate());
    }
    Collections.sort(selectedDates);
    return selectedDates;
  }

  /** Returns a string summarizing what the client sent us for init() params. */
  private static String dbg(Date minDate, Date maxDate) {
    return "minDate: " + minDate + "\nmaxDate: " + maxDate;
  }

  /** Clears out the hours/minutes/seconds/millis of a Calendar. */
  static void setMidnight(Calendar cal) {
    cal.set(HOUR_OF_DAY, 0);
    cal.set(MINUTE, 0);
    cal.set(SECOND, 0);
    cal.set(MILLISECOND, 0);
  }

  private class CellClickedListener implements MonthView.Listener {
    @Override public void handleClick(MonthCellDescriptor cell) {
      Date clickedDate = cell.getDate();

      if (cellClickInterceptor != null && cellClickInterceptor.onCellClicked(clickedDate)) {
        return;
      }
      if (!betweenDates(clickedDate, minCal, maxCal) || !isDateSelectable(clickedDate)) {
        if (invalidDateListener != null) {
          invalidDateListener.onInvalidDateSelected(clickedDate);
        }
      } else {
        boolean wasSelected = doSelectDate(clickedDate, cell);

        if (dateListener != null) {
          if (wasSelected) {
            dateListener.onDateSelected(clickedDate);
          } else {
            dateListener.onDateUnselected(clickedDate);
          }
        }
      }
    }
  }

  /**
   * Select a new date.  Respects the {@link SelectionMode} this CalendarPickerView is configured
   * with: if you are in {@link SelectionMode#SINGLE}, the previously selected date will be
   * un-selected.  In {@link SelectionMode#MULTIPLE}, the new date will be added to the list of
   * selected dates.
   * <p>
   * If the selection was made (selectable date, in range), the view will scroll to the newly
   * selected date if it's not already visible.
   *
   * @return - whether we were able to set the date
   */
  public boolean selectDate(Date date) {
    return selectDate(date, false);
  }

  /**
   * Select a new date.  Respects the {@link SelectionMode} this CalendarPickerView is configured
   * with: if you are in {@link SelectionMode#SINGLE}, the previously selected date will be
   * un-selected.  In {@link SelectionMode#MULTIPLE}, the new date will be added to the list of
   * selected dates.
   * <p>
   * If the selection was made (selectable date, in range), the view will scroll to the newly
   * selected date if it's not already visible.
   *
   * @return - whether we were able to set the date
   */
  public boolean selectDate(Date date, boolean smoothScroll) {
    validateDate(date);

    MonthCellWithMonthIndex monthCellWithMonthIndex = getMonthCellWithIndexByDate(date);
    if (monthCellWithMonthIndex == null || !isDateSelectable(date)) {
      return false;
    }
    boolean wasSelected = doSelectDate(date, monthCellWithMonthIndex.cell);
    if (wasSelected) {
      scrollToSelectedMonth(monthCellWithMonthIndex.monthIndex, smoothScroll);
    }
    return wasSelected;
  }

  /**
   * Use {@link DateUtils} to format the dates.
   *
   * @see DateUtils
   */
  private String formatMonthDate(Date date) {
    int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
            | DateUtils.FORMAT_NO_MONTH_DAY;

    // Save default Locale
    Locale defaultLocale = Locale.getDefault();

    // Set new default Locale, the reason to do that is DateUtils.formatDateTime uses
    // internally this method DateIntervalFormat.formatDateRange to format the date. And this
    // method uses the default locale.
    //
    // More details about the methods:
    // - DateUtils.formatDateTime: https://goo.gl/3YW52Q
    // - DateIntervalFormat.formatDateRange: https://goo.gl/RRmfK7
    Locale.setDefault(locale);

    String dateFormatted;
    if (displayAlwaysDigitNumbers
      && explicitlyNumericYearLocaleLanguages.contains(locale.getLanguage())) {
      StringBuilder sb = new StringBuilder();
      SimpleDateFormat sdfMonth = new SimpleDateFormat(getContext()
        .getString(R.string.month_only_name_format), locale);
      SimpleDateFormat sdfYear = new SimpleDateFormat(getContext()
        .getString(R.string.year_only_format), Locale.ENGLISH);
      dateFormatted = sb.append(sdfMonth.format(date.getTime())).append(" ")
        .append(sdfYear.format(date.getTime())).toString();
    } else {
      // Format date using the new Locale
      dateFormatted = DateUtils.formatDateRange(getContext(), monthFormatter,
        date.getTime(), date.getTime(), flags, timeZone.getID()).toString();
    }
    // Call setLength(0) on StringBuilder passed to the Formatter constructor to not accumulate
    // the results
    monthBuilder.setLength(0);

    // Restore default Locale to avoid generating any side effects
    Locale.setDefault(defaultLocale);

    return dateFormatted;
  }

  private void validateDate(Date date) {
    if (date == null) {
      throw new IllegalArgumentException("Selected date must be non-null.");
    }
    if (date.before(minCal.getTime()) || date.after(maxCal.getTime())) {
      throw new IllegalArgumentException(String.format(
          "SelectedDate must be between minDate and maxDate."
              + "%nminDate: %s%nmaxDate: %s%nselectedDate: %s", minCal.getTime(), maxCal.getTime(),
          date));
    }
  }

  private boolean doSelectDate(Date date, MonthCellDescriptor cell) {
    Calendar newlySelectedCal = Calendar.getInstance(timeZone, locale);
    newlySelectedCal.setTime(date);
    // Sanitize input: clear out the hours/minutes/seconds/millis.
    setMidnight(newlySelectedCal);

    // Clear any remaining range state.
    for (MonthCellDescriptor selectedCell : selectedCells) {
      selectedCell.setRangeState(RangeState.NONE);
    }

    switch (selectionMode) {
      case RANGE:
        if (selectedCals.size() > 1) {
          // We've already got a range selected: clear the old one.
          clearOldSelections();
        } else if (selectedCals.size() == 1 && newlySelectedCal.before(selectedCals.get(0))) {
          // We're moving the start of the range back in time: clear the old start date.
          clearOldSelections();
        }
        break;

      case MULTI_RANGE:
        // Still thinking what do
        break;

      case MULTIPLE:
        date = applyMultiSelect(date, newlySelectedCal);
        break;

      case SINGLE:
        clearOldSelections();
        break;
      default:
        throw new IllegalStateException("Unknown selectionMode " + selectionMode);
    }

    if (date != null) {
      // Select a new cell.
      if (selectedCells.size() == 0 || !selectedCells.get(0).equals(cell)) {
        selectedCells.add(cell);
        cell.setSelected(true);
      }

      if (selectionMode != SelectionMode.MULTI_RANGE) {
        selectedCals.add(newlySelectedCal);
      }

      if (selectionMode == SelectionMode.RANGE && selectedCells.size() > 1) {
        // Select all days in between start and end.
        Date start = selectedCells.get(0).getDate();
        Date end = selectedCells.get(1).getDate();
        selectedCells.get(0).setRangeState(RangeState.FIRST);
        selectedCells.get(1).setRangeState(RangeState.LAST);

        int startMonthIndex = cells.getIndexOfKey(monthKey(selectedCals.get(0)));
        int endMonthIndex = cells.getIndexOfKey(monthKey(selectedCals.get(1)));
        for (int monthIndex = startMonthIndex; monthIndex <= endMonthIndex; monthIndex++) {
          List<List<MonthCellDescriptor>> month = cells.getValueAtIndex(monthIndex);
          for (List<MonthCellDescriptor> week : month) {
            for (MonthCellDescriptor singleCell : week) {
              if (singleCell.getDate().after(start)
                  && singleCell.getDate().before(end)
                  && singleCell.isSelectable()) {
                singleCell.setSelected(true);
                singleCell.setRangeState(RangeState.MIDDLE);
                selectedCells.add(singleCell);
              }
            }
          }
        }
      } else if (selectionMode == SelectionMode.MULTI_RANGE) {
        // Find the range this date belongs to
        int nextClosestRange = findClosestGreaterRangeForDate(newlySelectedCal);
        int previousClosestRange = findClosestLowerRangeForDate(newlySelectedCal);

        if (nextClosestRange == -1 && previousClosestRange == -1) {
          // Simplest case, just add new range
          // We add a new one day range
          selectedCals.add(newlySelectedCal);
          selectedCals.add(newlySelectedCal);
        } else if (previousClosestRange != -1 && nextClosestRange != -1) {
          boolean wasAddedToRange = false;
          Calendar endOfPreviousRange = (Calendar) selectedCals.get(previousClosestRange).clone();
          Calendar startOfNextRange = (Calendar) selectedCals.get(nextClosestRange).clone();

          // Test if end of prev range is one day before the new date
          endOfPreviousRange.add(Calendar.DAY_OF_YEAR, 1);
          if (endOfPreviousRange.get(Calendar.DAY_OF_YEAR) == newlySelectedCal.get(Calendar.DAY_OF_YEAR)) {
            selectedCals.set(previousClosestRange, newlySelectedCal);
            wasAddedToRange = true;
          }

          // Test if the start of the next range is one day after the new data
          startOfNextRange.add(Calendar.DAY_OF_YEAR, -1);
          if (startOfNextRange.get(Calendar.DAY_OF_YEAR) == newlySelectedCal.get(Calendar.DAY_OF_YEAR)) {
            selectedCals.set(nextClosestRange, newlySelectedCal);
            wasAddedToRange = true;
          }

          if (endOfPreviousRange.get(Calendar.DAY_OF_YEAR) == startOfNextRange.get(Calendar.DAY_OF_YEAR)) {
            // We now 'merge' the ranges

            // The end of the next closest range becomes the end of the previos range
            selectedCals.set(previousClosestRange, selectedCals.get(nextClosestRange + 1));

            // We remove the second range
            selectedCals.remove(nextClosestRange);
            // We use the same index since once we remove it the first one
            // the list size will reduce
            selectedCals.remove(nextClosestRange);
          }

          if (!wasAddedToRange) {
            selectedCals.add(newlySelectedCal);
            selectedCals.add(newlySelectedCal);
          }
        } else if (nextClosestRange != -1 && previousClosestRange == -1) {
          // Has upper range, check if we should set it in there or create a new range
          // Check if we can make the new date the new start for the range
          Calendar test = (Calendar) newlySelectedCal.clone();
          test.add(Calendar.DAY_OF_YEAR, 1);

          if (test.get(Calendar.DAY_OF_YEAR)
                  == selectedCals.get(nextClosestRange).get(Calendar.DAY_OF_YEAR)) {
              // It means the #newlySelectedCal it's one day before the closest next range
              // We set the newly created date as the start of the next range
            selectedCals.set(nextClosestRange, newlySelectedCal);
          } else {
            selectedCals.add(newlySelectedCal);
            selectedCals.add(newlySelectedCal);
          }
        } else if (previousClosestRange != -1 && nextClosestRange == -1) {
          // Has lower range, check if we should set it in there or create a new range
          Calendar test = (Calendar)newlySelectedCal.clone();
          test.add(Calendar.DAY_OF_YEAR, -1);

          if (test.get(Calendar.DAY_OF_YEAR)
                  == selectedCals.get(previousClosestRange).get(Calendar.DAY_OF_YEAR)) {
            selectedCals.set(previousClosestRange, newlySelectedCal);
          } else {
            selectedCals.add(newlySelectedCal);
            selectedCals.add(newlySelectedCal);
          }
        }

        // Now the calendars are updated we need to mark the range state for
        // each of the cells
        for (int i = 0; i < selectedCals.size(); i+=2) {
          // Select all days in between start and end.
          Calendar start = selectedCals.get(i);
          Calendar end = selectedCals.get(i + 1);

          int startMonthIndex = cells.getIndexOfKey(monthKey(start));
          int endMonthIndex = cells.getIndexOfKey(monthKey(end));
          for (int monthIndex = startMonthIndex; monthIndex <= endMonthIndex; monthIndex++) {
            List<List<MonthCellDescriptor>> month = cells.getValueAtIndex(monthIndex);
            for (List<MonthCellDescriptor> week : month) {
              for (MonthCellDescriptor singleCell : week) {
                if (singleCell.getDate().after(start.getTime())
                        && singleCell.getDate().before(end.getTime())
                        && singleCell.isSelectable()) {
                  singleCell.setSelected(true);
                  singleCell.setRangeState(RangeState.MIDDLE);
//                  selectedCells.add(singleCell);
                } else if (singleCell.getDate().getDay() == start.get(Calendar.DAY_OF_YEAR) && singleCell.getDate().getYear() == start.get(Calendar.YEAR)) {
                  singleCell.setRangeState(RangeState.FIRST);
                } else if (singleCell.getDate().getDay() == end.get(Calendar.DAY_OF_YEAR) && singleCell.getDate().getYear() == end.get(Calendar.YEAR)) {
                  singleCell.setRangeState(RangeState.LAST);
                }
              }
            }
          }
        }

      }
    }

    // Update the adapter.
    validateAndUpdate();
    return date != null;
  }

  private int findClosestLowerRangeForDate(Calendar newCal) {
    if (newCal == null) return -1;

    int index = -1;
    for (int i = 1; i < selectedCals.size(); i+= 2) {
      Calendar end = selectedCals.get(i);

      if (end.before(newCal)) {
        if (index == -1 ||
                Math.abs(newCal.getTime().getTime() - end.getTime().getTime()) <
                         Math.abs(newCal.getTime().getTime() - selectedCals.get(index).getTime().getTime())) {
          index = i;
        }
      }
    }

    return index;
  }

  private int findClosestGreaterRangeForDate(Calendar newCal) {
    if (newCal == null) return -1;

    int index = -1;
    for (int i = 0; i < selectedCals.size(); i+= 2) {
      Calendar start = selectedCals.get(i);

      if (start.after(newCal)) {
        if (index == -1 ||
                Math.abs(start.getTime().getTime() - newCal.getTime().getTime()) <
                        Math.abs(selectedCals.get(index).getTime().getTime() - newCal.getTime().getTime())) {
          index = i;
        }
      }
    }

    return index;
  }

  private String monthKey(Calendar cal) {
    return cal.get(YEAR) + "-" + cal.get(MONTH);
  }

  private String monthKey(MonthDescriptor month) {
    return month.getYear() + "-" + month.getMonth();
  }

  private void clearOldSelections() {
    for (MonthCellDescriptor selectedCell : selectedCells) {
      // De-select the currently-selected cell.
      selectedCell.setSelected(false);

      if (dateListener != null) {
        Date selectedDate = selectedCell.getDate();

        if (selectionMode == SelectionMode.RANGE || selectionMode == SelectionMode.MULTI_RANGE) {
          int index = selectedCells.indexOf(selectedCell);
          if (index == 0 || index == selectedCells.size() - 1) {
            dateListener.onDateUnselected(selectedDate);
          }
        } else {
          dateListener.onDateUnselected(selectedDate);
        }
      }
    }
    selectedCells.clear();
    selectedCals.clear();
  }

  private Date applyMultiSelect(Date date, Calendar selectedCal) {
    for (MonthCellDescriptor selectedCell : selectedCells) {
      if (selectedCell.getDate().equals(date)) {
        // De-select the currently-selected cell.
        selectedCell.setSelected(false);
        selectedCells.remove(selectedCell);
        date = null;
        break;
      }
    }
    for (Calendar cal : selectedCals) {
      if (sameDate(cal, selectedCal)) {
        selectedCals.remove(cal);
        break;
      }
    }
    return date;
  }

  public void highlightDates(Collection<Date> dates) {
    for (Date date : dates) {
      validateDate(date);

      MonthCellWithMonthIndex monthCellWithMonthIndex = getMonthCellWithIndexByDate(date);
      if (monthCellWithMonthIndex != null) {
        Calendar newlyHighlightedCal = Calendar.getInstance(timeZone, locale);
        newlyHighlightedCal.setTime(date);
        MonthCellDescriptor cell = monthCellWithMonthIndex.cell;

        highlightedCells.add(cell);
        highlightedCals.add(newlyHighlightedCal);
        cell.setHighlighted(true);
      }
    }

    validateAndUpdate();
  }

  public void clearSelectedDates() {
      for (MonthCellDescriptor selectedCell : selectedCells) {
          selectedCell.setRangeState(RangeState.NONE);
      }

      clearOldSelections();
      validateAndUpdate();
  }

  public void clearHighlightedDates() {
    for (MonthCellDescriptor cal : highlightedCells) {
      cal.setHighlighted(false);
    }
    highlightedCells.clear();
    highlightedCals.clear();

    validateAndUpdate();
  }

  /** Hold a cell with a month-index. */
  private static class MonthCellWithMonthIndex {
    MonthCellDescriptor cell;
    int monthIndex;

    MonthCellWithMonthIndex(MonthCellDescriptor cell, int monthIndex) {
      this.cell = cell;
      this.monthIndex = monthIndex;
    }
  }

  /** Return cell and month-index (for wasScrolling) for a given Date. */
  private MonthCellWithMonthIndex getMonthCellWithIndexByDate(Date date) {
    Calendar searchCal = Calendar.getInstance(timeZone, locale);
    searchCal.setTime(date);
    String monthKey = monthKey(searchCal);
    Calendar actCal = Calendar.getInstance(timeZone, locale);

    int index = cells.getIndexOfKey(monthKey);
    List<List<MonthCellDescriptor>> monthCells = cells.get(monthKey);
    for (List<MonthCellDescriptor> weekCells : monthCells) {
      for (MonthCellDescriptor actCell : weekCells) {
        actCal.setTime(actCell.getDate());
        if (sameDate(actCal, searchCal) && actCell.isSelectable()) {
          return new MonthCellWithMonthIndex(actCell, index);
        }
      }
    }
    return null;
  }

  private class MonthAdapter extends RecyclerView.Adapter<MonthViewHolder> {
    private final LayoutInflater inflater;

    private MonthAdapter() {
      inflater = LayoutInflater.from(getContext());
    }

    @Override public int getItemCount() {
      return months.size();
    }

    @Override public long getItemId(int position) {
      return position;
    }

    @Override
    public MonthViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

      MonthView item =
              MonthView.create(parent, inflater, weekdayNameFormat, listener, today, dividerColor,
                      dayBackgroundResId, dayTextColorResId, titleTextStyle, displayHeader,
                      headerTextColor, displayDayNamesHeaderRow, displayAlwaysDigitNumbers,
                      decorators, locale, dayViewAdapter);


      item.setDecorators(decorators);

      return new MonthViewHolder(item);
    }

    @Override
    public void onBindViewHolder(MonthViewHolder holder, int position) {
      if (monthsReverseOrder) {
        position = months.size() - position - 1;
      }
      holder.init(months.get(position), cells.getValueAtIndex(position), displayOnly,
              titleTypeface, dateTypeface);
    }
  }

  public class MonthViewHolder<V> extends RecyclerView.ViewHolder {

    public MonthViewHolder(View itemView) {
      super(itemView);
    }

    public void init(MonthDescriptor descriptor, List<List<MonthCellDescriptor>> cells, boolean displayOnly, Typeface typeface, Typeface dateTypeface) {
      ((MonthView)itemView).init(descriptor, cells, displayOnly, typeface, dateTypeface);
    }
  }

  List<List<MonthCellDescriptor>> getMonthCells(MonthDescriptor month, Calendar startCal) {
    Calendar cal = Calendar.getInstance(timeZone, locale);
    cal.setTime(startCal.getTime());
    List<List<MonthCellDescriptor>> cells = new ArrayList<>();
    cal.set(DAY_OF_MONTH, 1);
    int firstDayOfWeek = cal.get(DAY_OF_WEEK);
    int offset = cal.getFirstDayOfWeek() - firstDayOfWeek;
    if (offset > 0) {
      offset -= 7;
    }
    cal.add(Calendar.DATE, offset);

    Calendar minSelectedCal = minDate(selectedCals);
    Calendar maxSelectedCal = maxDate(selectedCals);

    while ((cal.get(MONTH) < month.getMonth() + 1 || cal.get(YEAR) < month.getYear()) //
        && cal.get(YEAR) <= month.getYear()) {
      Logr.d("Building week row starting at %s", cal.getTime());
      List<MonthCellDescriptor> weekCells = new ArrayList<>();
      cells.add(weekCells);
      for (int c = 0; c < 7; c++) {
        Date date = cal.getTime();
        boolean isCurrentMonth = cal.get(MONTH) == month.getMonth();
        boolean isSelected = isCurrentMonth && containsDate(selectedCals, cal);
        boolean isSelectable =
            isCurrentMonth && betweenDates(cal, minCal, maxCal) && isDateSelectable(date);
        boolean isToday = sameDate(cal, today);
        boolean isHighlighted = containsDate(highlightedCals, cal);
        int value = cal.get(DAY_OF_MONTH);

        RangeState rangeState = RangeState.NONE;
        if (selectedCals.size() > 1) {
          if (sameDate(minSelectedCal, cal)) {
            rangeState = RangeState.FIRST;
          } else if (sameDate(maxDate(selectedCals), cal)) {
            rangeState = RangeState.LAST;
          } else if (betweenDates(cal, minSelectedCal, maxSelectedCal)) {
            rangeState = RangeState.MIDDLE;
          }
        }

        weekCells.add(
            new MonthCellDescriptor(date, isCurrentMonth, isSelectable, isSelected, isToday,
                isHighlighted, value, rangeState));
        cal.add(DATE, 1);
      }
    }
    return cells;
  }

  private boolean containsDate(List<Calendar> selectedCals, Date date) {
    Calendar cal = Calendar.getInstance(timeZone, locale);
    cal.setTime(date);
    return containsDate(selectedCals, cal);
  }

  private static boolean containsDate(List<Calendar> selectedCals, Calendar cal) {
    for (Calendar selectedCal : selectedCals) {
      if (sameDate(cal, selectedCal)) {
        return true;
      }
    }
    return false;
  }

  private static Calendar minDate(List<Calendar> selectedCals) {
    if (selectedCals == null || selectedCals.size() == 0) {
      return null;
    }
    Collections.sort(selectedCals);
    return selectedCals.get(0);
  }

  private static Calendar maxDate(List<Calendar> selectedCals) {
    if (selectedCals == null || selectedCals.size() == 0) {
      return null;
    }
    Collections.sort(selectedCals);
    return selectedCals.get(selectedCals.size() - 1);
  }

  private static boolean sameDate(Calendar cal, Calendar selectedDate) {
    return cal.get(MONTH) == selectedDate.get(MONTH)
        && cal.get(YEAR) == selectedDate.get(YEAR)
        && cal.get(DAY_OF_MONTH) == selectedDate.get(DAY_OF_MONTH);
  }

  private static boolean betweenDates(Calendar cal, Calendar minCal, Calendar maxCal) {
    final Date date = cal.getTime();
    return betweenDates(date, minCal, maxCal);
  }

  static boolean betweenDates(Date date, Calendar minCal, Calendar maxCal) {
    final Date min = minCal.getTime();
    return (date.equals(min) || date.after(min)) // >= minCal
        && date.before(maxCal.getTime()); // && < maxCal
  }

  private static boolean sameMonth(Calendar cal, MonthDescriptor month) {
    return (cal.get(MONTH) == month.getMonth() && cal.get(YEAR) == month.getYear());
  }

  private boolean isDateSelectable(Date date) {
    return dateConfiguredListener == null || dateConfiguredListener.isDateSelectable(date);
  }

  public void setOnDateSelectedListener(OnDateSelectedListener listener) {
    dateListener = listener;
  }

  /**
   * Set a listener to react to user selection of a disabled date.
   *
   * @param listener the listener to set, or null for no reaction
   */
  public void setOnInvalidDateSelectedListener(OnInvalidDateSelectedListener listener) {
    invalidDateListener = listener;
  }

  /**
   * Set a listener used to discriminate between selectable and unselectable dates. Set this to
   * disable arbitrary dates as they are rendered.
   * <p>
   * Important: set this before you call {@link #init(Date, Date)} methods.  If called afterwards,
   * it will not be consistently applied.
   */
  public void setDateSelectableFilter(DateSelectableFilter listener) {
    dateConfiguredListener = listener;
  }

  /**
   * Set an adapter used to initialize {@link CalendarCellView} with custom layout.
   * <p>
   * Important: set this before you call {@link #init(Date, Date)} methods.  If called afterwards,
   * it will not be consistently applied.
   */
  public void setCustomDayView(DayViewAdapter dayViewAdapter) {
    this.dayViewAdapter = dayViewAdapter;
    if (null != adapter) {
      adapter.notifyDataSetChanged();
    }
  }

  /** Set a listener to intercept clicks on calendar cells. */
  public void setCellClickInterceptor(CellClickInterceptor listener) {
    cellClickInterceptor = listener;
  }

  /**
   * Interface to be notified when a new month has been selected
   */
  public interface OnMonthSelectedListener {
    void onMonthSelected(int month, int year);
  }

  /**
   * Interface to be notified when a new date is selected or unselected. This will only be called
   * when the user initiates the date selection.  If you call {@link #selectDate(Date)} this
   * listener will not be notified.
   *
   * @see #setOnDateSelectedListener(OnDateSelectedListener)
   */
  public interface OnDateSelectedListener {
    void onDateSelected(Date date);
    void onDateUnselected(Date date);
  }

  /**
   * Interface to be notified when an invalid date is selected by the user. This will only be
   * called when the user initiates the date selection. If you call {@link #selectDate(Date)} this
   * listener will not be notified.
   *
   * @see #setOnInvalidDateSelectedListener(OnInvalidDateSelectedListener)
   */
  public interface OnInvalidDateSelectedListener {
    void onInvalidDateSelected(Date date);
  }

  /**
   * Interface used for determining the selectability of a date cell when it is configured for
   * display on the calendar.
   *
   * @see #setDateSelectableFilter(DateSelectableFilter)
   */
  public interface DateSelectableFilter {
    boolean isDateSelectable(Date date);
  }

  /**
   * Interface to be notified when a cell is clicked and possibly intercept the click.  Return true
   * to intercept the click and prevent any selections from changing.
   *
   * @see #setCellClickInterceptor(CellClickInterceptor)
   */
  public interface CellClickInterceptor {
    boolean onCellClicked(Date date);
  }

  private class DefaultOnInvalidDateSelectedListener implements OnInvalidDateSelectedListener {
    @Override public void onInvalidDateSelected(Date date) {
      String errMessage =
          getResources().getString(R.string.invalid_date, fullDateFormat.format(minCal.getTime()),
              fullDateFormat.format(maxCal.getTime()));
      Toast.makeText(getContext(), errMessage, Toast.LENGTH_SHORT).show();
    }
  }
}
