/*
 * Copyright 2015 Ayuget
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ayuget.redface.ui.fragment;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.AttrRes;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v4.view.PagerTabStrip;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.Toolbar;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.ayuget.redface.R;
import com.ayuget.redface.account.UserManager;
import com.ayuget.redface.data.api.MDEndpoints;
import com.ayuget.redface.data.api.MDService;
import com.ayuget.redface.data.api.model.Topic;
import com.ayuget.redface.data.api.model.TopicSearchResult;
import com.ayuget.redface.data.api.model.User;
import com.ayuget.redface.data.quote.QuotedMessagesCache;
import com.ayuget.redface.data.rx.EndlessObserver;
import com.ayuget.redface.data.rx.SubscriptionHandler;
import com.ayuget.redface.settings.RedfaceSettings;
import com.ayuget.redface.ui.UIConstants;
import com.ayuget.redface.ui.activity.MultiPaneActivity;
import com.ayuget.redface.ui.activity.ReplyActivity;
import com.ayuget.redface.ui.activity.WritePrivateMessageActivity;
import com.ayuget.redface.ui.adapter.TopicPageAdapter;
import com.ayuget.redface.ui.event.GoToPostEvent;
import com.ayuget.redface.ui.event.OverriddenPagePosition;
import com.ayuget.redface.ui.event.PageRefreshRequestEvent;
import com.ayuget.redface.ui.event.PageSelectedEvent;
import com.ayuget.redface.ui.event.ScrollToPositionEvent;
import com.ayuget.redface.ui.event.ShowAllSpoilersEvent;
import com.ayuget.redface.ui.event.TopicPageCountUpdatedEvent;
import com.ayuget.redface.ui.event.UnquoteAllPostsEvent;
import com.ayuget.redface.ui.event.WritePrivateMessageEvent;
import com.ayuget.redface.ui.misc.PagePosition;
import com.ayuget.redface.ui.misc.SnackbarHelper;
import com.ayuget.redface.ui.misc.TopicPosition;
import com.ayuget.redface.ui.misc.UiUtils;
import com.ayuget.redface.ui.view.TopicPageView;
import com.hannesdorfmann.fragmentargs.annotation.Arg;
import com.hannesdorfmann.fragmentargs.annotation.FragmentWithArgs;
import com.squareup.otto.Subscribe;
import com.squareup.phrase.Phrase;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.InjectView;
import timber.log.Timber;

import static android.content.DialogInterface.BUTTON_POSITIVE;

@FragmentWithArgs
public class TopicFragment extends ToolbarFragment implements ViewPager.OnPageChangeListener, TopicPageView.OnQuoteListener {
    private static final String ARG_TOPIC_POSITIONS_STACK = "topicPositionsStack";
    private static final String ARG_QUOTED_MESSAGES_CACHE = "quotedMessagesCache";
    private static final String ARG_IS_IN_ACTION_MODE = "isInActionMode";
    private static final String ARG_IS_IN_SEARCH_MODE = "isInSearchMode";
    private static final String ARG_TOPIC = "topic";

    private static final int UNFLAG_ACTION = 42;

    private TopicPageAdapter topicPageAdapter;

    private EditText goToPageEditText;

    private ArrayList<TopicPosition> topicPositionsStack;

    /**
     * Delegate to handle the "unflag" action subscription properly
     */
    private SubscriptionHandler<User, Boolean> unflagSubscriptionHandler = new SubscriptionHandler<>();

    /**
     * Delegate to handle the "fetch quote bbcode" action subscription properly
     */
    private SubscriptionHandler<Long, String> quoteHandler = new SubscriptionHandler<>();

    /**
     * Delegate to handle the "search" action mode subscription properly
     */
    private SubscriptionHandler<Topic, TopicSearchResult> topicSearchSubscriptionHandler = new SubscriptionHandler<>();

    /**
     * Used to display a contextual action bar when multi-quote mode is enabled
     */
    private ActionMode quoteActionMode;

    /**
     * In-memory cache for quoted messages
     */
    private QuotedMessagesCache quotedMessagesCache;

    @Inject
    MDEndpoints mdEndpoints;

    @Inject
    UserManager userManager;

    @Inject
    MDService mdService;

    @Inject
    RedfaceSettings settings;

    @InjectView(R.id.pager)
    ViewPager pager;

    @InjectView(R.id.titlestrip)
    PagerTabStrip pagerTitleStrip;

    @InjectView(R.id.reply_button)
    FloatingActionButton replyButton;

    @InjectView(R.id.move_to_top_button)
    FloatingActionButton moveToTopButton;

    @InjectView(R.id.move_to_bottom_button)
    FloatingActionButton moveToBottomButton;

    TextView topicWordSearch;
    TextView topicAuthorSearch;

    @Arg
    Topic topic;

    @Arg
    int initialPage;

    @Arg
    PagePosition initialPagePosition;

    /**
     * Page currently displayed in the viewPager
     */
    int currentPage;

    /**
     * Overridden page position for next loaded page. Will be nulled immediately after target
     * page is loaded. We need such a transient value because ViewPager setCurrentItem method
     * is not synchronous.
     */
    OverriddenPagePosition overriddenPagePosition;

    private boolean isInActionMode = false;
    private boolean isInSearchMode = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Timber.d("[Topic=%d], initialPage = %d, initialPagePosition = %s", topic.id(), initialPage, initialPagePosition);

        // Start at initial page, currentPage value is updated via the onPageSelected
        // listener on the ViewPager
        currentPage = initialPage;

        if (topicPageAdapter == null) {
            topicPageAdapter = new TopicPageAdapter(getChildFragmentManager(), topic, initialPage, initialPagePosition);
        }

        if (savedInstanceState != null) {
            topicPositionsStack = savedInstanceState.getParcelableArrayList(ARG_TOPIC_POSITIONS_STACK);
            quotedMessagesCache = savedInstanceState.getParcelable(ARG_QUOTED_MESSAGES_CACHE);
            isInActionMode = savedInstanceState.getBoolean(ARG_IS_IN_ACTION_MODE);
            isInSearchMode = savedInstanceState.getBoolean(ARG_IS_IN_SEARCH_MODE);
        }

        if (topicPositionsStack == null) {
            topicPositionsStack = new ArrayList<>();
        }

        if (quotedMessagesCache == null) {
            quotedMessagesCache = new QuotedMessagesCache();
        }

        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View rootView = inflateRootView(R.layout.fragment_topic, inflater, container);
        pagerTitleStrip.setDrawFullUnderline(false);
        pagerTitleStrip.setTabIndicatorColor(getResources().getColor(R.color.theme_primary));
        pager.setAdapter(topicPageAdapter);
        pager.setCurrentItem(initialPage - 1);

        if (userManager.getActiveUser().isGuest()) {
            replyButton.setVisibility(View.INVISIBLE);
        } else {
            replyButton.setOnClickListener((v) -> {
                if (isInSearchMode) {
                    searchInTopic();
                }
                else {
                    replyToTopic();
                }
            });
        }

        setupQuickNavigationButtons();

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();

        if (quotedMessagesCache.size() > 0 && isInActionMode) {
            startMultiQuoteAction(false);
        }

        if (isInSearchMode) {
            startSearchMode(getToolbar(), false);
        }

        pager.addOnPageChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();

        if (isInSearchMode) {
            stopSearchMode(getToolbar(), false);
        }
        pager.removeOnPageChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (quoteActionMode != null) {
            quoteActionMode.finish();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelableArrayList(ARG_TOPIC_POSITIONS_STACK, topicPositionsStack);
        outState.putBoolean(ARG_IS_IN_SEARCH_MODE, isInSearchMode);

        if (isInActionMode) {
            outState.putParcelable(ARG_QUOTED_MESSAGES_CACHE, quotedMessagesCache);
            outState.putBoolean(ARG_IS_IN_ACTION_MODE, isInActionMode);
        }
        else {
            quotedMessagesCache.clear();
        }
    }

    @Override
    public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
        // Ignore event
    }

    @Override
    public void onPageSelected(int position) {
        currentPage = position + 1;

        if (overriddenPagePosition != null) {
            Timber.d("Page %d is now selected, overriding page position to : %s", currentPage, overriddenPagePosition);
        }
        bus.post(PageSelectedEvent.create(topic, currentPage, overriddenPagePosition));

        overriddenPagePosition = null;
    }

    @Override
    public void onPageScrollStateChanged(int state) {
        // Ignore event
    }

    @Override
    public void onCreateOptionsMenu(Toolbar toolbar) {
        toolbar.inflateMenu(R.menu.menu_topic);

        if (userManager.isActiveUserLoggedIn() && !topic.isPrivateMessage()) {
            // Small hack to find the last added item order...
            Menu existingMenu = toolbar.getMenu();
            int lastAction = existingMenu.size() > 0 ? existingMenu.getItem(existingMenu.size() - 1).getOrder() : 0;

            toolbar.getMenu().add(Menu.NONE, UNFLAG_ACTION, lastAction + 100, getString(R.string.action_unflag_topic));
        }

        setupIntraTopicSearch(toolbar);
    }

    private void setupIntraTopicSearch(Toolbar toolbar) {
        MenuItem topicSearchItem = toolbar.getMenu().findItem(R.id.action_search);
        MenuItem topicSearchFiltersItem = toolbar.getMenu().findItem(R.id.action_topic_search_filters);

        if (topicSearchItem != null && topicSearchFiltersItem != null) {
            topicSearchFiltersItem.setVisible(false);

            topicWordSearch = topicSearchItem.getActionView().findViewById(R.id.topic_word_search_text);
            topicAuthorSearch = topicSearchItem.getActionView().findViewById(R.id.topic_username_search_text);

            topicSearchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(MenuItem menuItem) {
                    topicSearchFiltersItem.setVisible(true);
                    isInSearchMode = true;
                    startSearchMode(toolbar, true);
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(MenuItem menuItem) {
                    topicSearchFiltersItem.setVisible(false);
                    isInSearchMode = false;
                    stopSearchMode(toolbar, true);
                    return true;
                }
            });
        }
    }

    private void startSearchMode(Toolbar toolbar, boolean progressively) {
        if (progressively) {
            tintToolbarProgressively(toolbar, R.attr.colorPrimary, R.attr.statusBarBackgroundColor, R.attr.actionModeBackground, R.attr.actionModeBackground);
        }
        else {
            tintToolbarImmediately(toolbar, R.attr.actionModeBackground, R.attr.actionModeBackground);
        }

        replyButton.setImageResource(R.drawable.ic_search_white_24dp);
    }

    private void stopSearchMode(Toolbar toolbar, boolean progressively) {
        if (progressively) {
            tintToolbarProgressively(toolbar, R.attr.actionModeBackground, R.attr.actionModeBackground, R.attr.colorPrimary, R.attr.statusBarBackgroundColor);
        }
        else {
            tintToolbarImmediately(toolbar, R.attr.colorPrimary, R.attr.statusBarBackgroundColor);
        }

        replyButton.setImageResource(R.drawable.ic_create_white_24dp);
    }

    private void tintToolbarImmediately(Toolbar toolbar, @AttrRes int targetToolbarColor, @AttrRes int targetStatusBarColor) {
        int toolbarColor = UiUtils.resolveColorAttribute(getContext(), targetToolbarColor);
        int statusBarColor = UiUtils.resolveColorAttribute(getContext(), targetStatusBarColor);

        toolbar.setBackgroundColor(toolbarColor);
        pagerTitleStrip.setBackgroundColor(toolbarColor);
        replyButton.setBackgroundTintList(ColorStateList.valueOf(toolbarColor));

        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getActivity().getWindow().setStatusBarColor(statusBarColor);
        }
    }

    private void tintToolbarProgressively(Toolbar toolbar, @AttrRes int currentToolbarColor, @AttrRes int currentStatusBarColor, @AttrRes int targetToolbarColor, @AttrRes  int targetStatusBarColor) {
        int startToolbarColor = UiUtils.resolveColorAttribute(getContext(), currentToolbarColor);
        int endToolbarColor = UiUtils.resolveColorAttribute(getContext(), targetToolbarColor);
        int startStatusBarColor = UiUtils.resolveColorAttribute(getContext(), currentStatusBarColor);
        int endStatusBarColor = UiUtils.resolveColorAttribute(getContext(), targetStatusBarColor);

        ValueAnimator toolbarColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startToolbarColor, endToolbarColor);
        ValueAnimator statusBarColorAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), startStatusBarColor, endStatusBarColor);

        toolbarColorAnimator.addUpdateListener(v -> {
            int animatedValue = (Integer) v.getAnimatedValue();
            toolbar.setBackgroundColor(animatedValue);
            pagerTitleStrip.setBackgroundColor(animatedValue);
            replyButton.setBackgroundTintList(ColorStateList.valueOf(animatedValue));
        });
        statusBarColorAnimator.addUpdateListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                getActivity().getWindow().setStatusBarColor((Integer) v.getAnimatedValue());
            }
        });

        int animationDuration = getContext().getResources().getInteger(R.integer.searchModeDuration);
        toolbarColorAnimator.setDuration(animationDuration);
        toolbarColorAnimator.setStartDelay(0);
        toolbarColorAnimator.start();
        statusBarColorAnimator.setDuration(animationDuration);
        statusBarColorAnimator.setStartDelay(0);
        statusBarColorAnimator.start();
    }

    @Override
    public void onToolbarInitialized(Toolbar toolbar) {
        MultiPaneActivity hostActivity = (MultiPaneActivity) getActivity();

        if (! hostActivity.isTwoPaneMode()) {
            showUpButton();
        }

        toolbar.setTitle(topic.title());
    }

    /**
     * Method to be invoked by child fragments when a batch operation on Posts has started.
     */
    public void onBatchOperation(boolean active) {
        MultiPaneActivity hostActivity = (MultiPaneActivity) getActivity();

        if (! hostActivity.isTwoPaneMode()) {
            if (active) {
                tintToolbarProgressively(getToolbar(), R.attr.colorPrimary, R.attr.statusBarBackgroundColor, R.attr.actionModeBackground, R.attr.actionModeBackground);
            }
            else {
                tintToolbarImmediately(getToolbar(), R.attr.colorPrimary, R.attr.statusBarBackgroundColor);
            }
        }
    }

    /**
     * Event fired by the data layer when we detect that new pages have been added
     * to a topic. It allows us to update the UI properly. This is completely mandatory
     * for "hot" topics, when a lot of content is added in a short period of time.
     */
    @SuppressWarnings("unused")
    @Subscribe
    public void onTopicPageCountUpdated(TopicPageCountUpdatedEvent event) {
        if (event.getTopic().id() == topic.id()) {
            topic = topic.withPagesCount(event.getNewPageCount());
            topicPageAdapter.notifyTopicUpdated(topic);
        }
    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onWritePrivateMessage(WritePrivateMessageEvent event) {
        MultiPaneActivity hostActivity = (MultiPaneActivity) getActivity();

        if (hostActivity.canLaunchReplyActivity()) {
            hostActivity.setCanLaunchReplyActivity(false);

            Intent intent = new Intent(getActivity(), WritePrivateMessageActivity.class);
            intent.putExtra(UIConstants.ARG_PM_RECIPIENT, event.getRecipient());

            getActivity().startActivityForResult(intent, UIConstants.NEW_PM_REQUEST_CODE);
        }

    }

    @SuppressWarnings("unused")
    @Subscribe
    public void onGoToPost(GoToPostEvent event) {
        Timber.d("Received GoToPostEvent : %s", event);
        Timber.d("Current page is = %d", currentPage);
        int currentScrollY = event.getTopicPageView().getScrollY();
        topicPositionsStack.add(TopicPosition.create(currentPage, currentScrollY));

        if (currentPage == event.getPage()) {
            event.getTopicPageView().setPagePosition(event.getTargetPost());
        }
        else {
            overriddenPagePosition = OverriddenPagePosition.toPost(event.getTargetPost());
            if (pager != null) {
                pager.setCurrentItem(event.getPage() - 1);
            }
        }
    }

    /**
     * Callback called by the activity when the back key has been pressed
     * @return true if event was consumed, false otherwise
     */
    public boolean onBackPressed() {
        if (isInSearchMode) {
            getToolbar().collapseActionView();
            return true;
        }
        else if (topicPositionsStack.size() == 0) {
            return false;
        }
        else {
            TopicPosition topicPosition = topicPositionsStack.remove(topicPositionsStack.size() - 1);

            if (currentPage == topicPosition.page()) {
                OverriddenPagePosition targetPagePosition = OverriddenPagePosition.toScrollY(topicPosition.pageScrollYTarget());
                bus.post(ScrollToPositionEvent.create(topic, currentPage, targetPagePosition));
            }
            else {
                overriddenPagePosition = OverriddenPagePosition.toScrollY(topicPosition.pageScrollYTarget());
                pager.setCurrentItem(topicPosition.page() - 1);
            }

            return true;
        }
    }

    /**
     * Returns the currently displayed topic
     */
    public Topic getTopic() {
        return topic;
    }

    /**
     * Returns the initial displayed page
     */
    public int getCurrentPage() {
        return currentPage;
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {

    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        String topicUrl = mdEndpoints.topic(topic, currentPage);
        switch (id) {
            case R.id.action_refresh_topic:
                bus.post(new PageRefreshRequestEvent(topic));
                break;
            case R.id.action_show_all_spoilers:
                bus.post(new ShowAllSpoilersEvent(topic, currentPage));
                break;
            case R.id.action_go_to_first_page:
                pager.setCurrentItem(0);
                return true;
            case R.id.action_go_to_last_page:
                pager.setCurrentItem(topic.pagesCount() - 1);
                return true;

            case R.id.action_go_to_specific_page:
                showGoToPageDialog();
                return true;

            case R.id.action_copy_link:
                UiUtils.copyToClipboard(getActivity(), topicUrl);
                break;
            case R.id.action_share:
                UiUtils.shareText(getActivity(), topicUrl);
                break;
            case UNFLAG_ACTION:
                unflagTopic();
                break;
            case R.id.action_open_in_browser:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(topicUrl)));
                break;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Unflags current topic.
     */
    private void unflagTopic() {
        User activeUser = userManager.getActiveUser();
        subscribe(unflagSubscriptionHandler.load(activeUser, mdService.unflagTopic(activeUser, topic), new EndlessObserver<Boolean>() {
            @Override
            public void onNext(Boolean aBoolean) {
                SnackbarHelper.make(TopicFragment.this, R.string.flag_successfully_removed).show();
            }

            @Override
            public void onError(Throwable throwable) {
                Timber.e(throwable, "Error while removing flag");
                SnackbarHelper.makeError(TopicFragment.this, R.string.error_removing_flag).show();
            }
        }));
    }

    /**
     * Clears internal navigation stack
     */
    @Override
    public void clearInternalStack() {
        topicPositionsStack.clear();
    }


    private void setupQuickNavigationButtons() {
        if (settings.areNavigationButtonsEnabled()) {
            float buttonsAlpha = 0.30f;
            moveToTopButton.setAlpha(buttonsAlpha);
            moveToBottomButton.setAlpha(buttonsAlpha);

            moveToTopButton.setOnClickListener((c) -> bus.post(ScrollToPositionEvent.create(topic, currentPage, OverriddenPagePosition.toTop())));
            moveToBottomButton.setOnClickListener((c) -> bus.post(ScrollToPositionEvent.create(topic, currentPage, OverriddenPagePosition.toBottom())));
        }
        else {
            moveToBottomButton.setVisibility(View.GONE);
            moveToTopButton.setVisibility(View.GONE);
        }
    }

    public void showGoToPageDialog() {
        AlertDialog dialog = new AlertDialog.Builder(getActivity(), themeManager.getActiveThemeStyle())
                .setView(R.layout.dialog_go_to_page)
                .setPositiveButton(R.string.dialog_go_to_page_positive_text, (dialog1, which) -> {
                    try {
                        int pageNumber = Integer.valueOf(goToPageEditText.getText().toString());
                        pager.setCurrentItem(pageNumber - 1);
                    }
                    catch (NumberFormatException e) {
                        Timber.e(e, "Invalid page number entered : %s", goToPageEditText.getText().toString());
                        SnackbarHelper.make(TopicFragment.this, R.string.invalid_page_number).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();


        final View positiveAction = dialog.getButton(BUTTON_POSITIVE);
        goToPageEditText = dialog.findViewById(R.id.page_number);

        TextView pagesCountView = dialog.findViewById(R.id.pages_count);
        pagesCountView.setText(Phrase.from(getActivity(), R.string.pages_count_currently).put("current_page", currentPage).put("pages_count", topic.pagesCount()).format());

        goToPageEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {

            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.toString().trim().length() > 0) {
                    try {
                        int pageNumber = Integer.valueOf(s.toString());
                        positiveAction.setEnabled(pageNumber >= 1 && pageNumber <= topic.pagesCount());
                    }
                    catch (NumberFormatException e) {
                        positiveAction.setEnabled(false);
                    }
                }
                else {
                    positiveAction.setEnabled(false);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {

            }
        });

        dialog.show();
        positiveAction.setEnabled(false);
    }

    @Override
    public void onPostQuoted(final int page, final long postId) {
        if (! isInActionMode) {
            startMultiQuoteAction(true);
        }

        subscribe(quoteHandler.load(postId, mdService.getQuote(userManager.getActiveUser(), topic, (int) postId), new EndlessObserver<String>() {
            @Override
            public void onNext(String quoteBBCode) {
                quotedMessagesCache.add(postId, page, quoteBBCode);
                updateQuotedMessagesCount();
            }

            @Override
            public void onError(Throwable throwable) {
                Timber.e(throwable, "Failed to add post to quoted list");
                Toast.makeText(getActivity(), R.string.post_failed_to_quote, Toast.LENGTH_SHORT).show();
            }
        }));
    }

    @Override
    public void onPostUnquoted(int page, long postId) {
        quotedMessagesCache.remove(postId);
        updateQuotedMessagesCount();

        if (quotedMessagesCache.size() == 0) {
            stopMultiQuoteAction();
        }
    }

    /**
     * Starts the multi-quote action mode
     */
    private void startMultiQuoteAction(boolean resetQuotedMessages) {
        onBatchOperation(true);

        if (resetQuotedMessages) {
            quotedMessagesCache.clear();
        }

        quoteActionMode = getActivity().startActionMode(new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                MenuInflater inflater = actionMode.getMenuInflater();
                inflater.inflate(R.menu.menu_multi_quote, menu);

                // Initial quotes cache size can be greater than 0, if we are recovering from a
                // previously saved state.
                if (quotedMessagesCache.size() > 1) {
                    actionMode.setTitle(Phrase.from(getContext(), R.string.quoted_messages_plural).put("count", quotedMessagesCache.size()).format());
                }
                else {
                    actionMode.setTitle(R.string.quoted_messages);
                }

                isInActionMode = true;

                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
                switch (menuItem.getItemId()) {
                    case R.id.action_multiquote:
                        replyToTopic();
                        return true;
                    default:
                        return false;
                }
            }

            @Override
            public void onDestroyActionMode(ActionMode actionMode) {
                Timber.d("Destroying action mode");
                stopMultiQuoteAction();
                isInActionMode = false;
            }
        });
    }

    /**
     * Starts the multi-quote action mode.
     *
     * Note : quoted messages cache is not cleared, because it's impossible (without hacks) to
     * differentiate when the action mode is actually destroyed by the user (back button pressed,
     * or close button in the CAB) from configuration changes (rotation, change of context, ...)
     */
    private void stopMultiQuoteAction() {
        if (quoteActionMode != null) {
            quoteActionMode.finish();
        }

        // Notify ViewPager fragments
        bus.post(new UnquoteAllPostsEvent());

        // Disable PagerIndicator batch color
        onBatchOperation(false);
    }

    /**
     * Updates the quote action mode title with the appropriate number of quoted messages
     */
    private void updateQuotedMessagesCount() {
        if (quoteActionMode != null) {
            if (quotedMessagesCache.size() > 1) {
                quoteActionMode.setTitle(Phrase.from(getContext(), R.string.quoted_messages_plural).put("count", quotedMessagesCache.size()).format());
            }
            else {
                quoteActionMode.setTitle(R.string.quoted_messages);
            }
        }
    }

    /**
     * Starts the reply activity without an initial content (or with the currently quoted context,
     * if present)
     */
    public void replyToTopic() {
        if (quotedMessagesCache.size() > 0 && isInActionMode) {
            String quotedContent = quotedMessagesCache.join();
            stopMultiQuoteAction();
            replyToTopic(quotedContent);

        }
        else {
            quotedMessagesCache.clear();
            replyToTopic(null);
        }
    }

    /**
     * Starts the reply activity with an initial content
     */
    private void replyToTopic(String initialContent) {
        MultiPaneActivity hostActivity = (MultiPaneActivity) getActivity();

        if (hostActivity.canLaunchReplyActivity()) {
            hostActivity.setCanLaunchReplyActivity(false);

            Intent intent = new Intent(getActivity(), ReplyActivity.class);
            intent.putExtra(ARG_TOPIC, topic);

            if (initialContent != null) {
                intent.putExtra(UIConstants.ARG_REPLY_CONTENT, initialContent);
            }

            getActivity().startActivityForResult(intent, UIConstants.REPLY_REQUEST_CODE);
        }
    }

    /**
     * Returns a list of all quoted posts for a given page
     */
    public List<Long> getPageQuotedPosts(int page) {
        return quotedMessagesCache.getPageQuotedMessages(page);
    }

    private void searchInTopic() {
        SnackbarHelper.make(TopicFragment.this, R.string.search_topic_in_progress).show();
        String wordSearchText = topicWordSearch.getText().toString();
        String authorSearchText = topicAuthorSearch.getText().toString();

        Timber.d("Word = '%s', Author = '%s'", wordSearchText, authorSearchText);

        subscribe(topicSearchSubscriptionHandler.load(topic, mdService.searchInTopic(userManager.getActiveUser(), topic, 0, wordSearchText, authorSearchText, true), new EndlessObserver<TopicSearchResult>() {
            @Override
            public void onNext(TopicSearchResult topicSearchResult) {
                Timber.d(topicSearchResult.toString());
            }

            @Override
            public void onError(Throwable throwable) {
                Timber.e(throwable, "Error while searching topic");
                SnackbarHelper.makeError(TopicFragment.this, R.string.search_topic_error).show();
            }
        }));
    }
}
