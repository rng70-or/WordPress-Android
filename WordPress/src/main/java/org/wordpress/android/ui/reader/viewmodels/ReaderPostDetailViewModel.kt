package org.wordpress.android.ui.reader.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import org.wordpress.android.R
import org.wordpress.android.datasets.wrappers.ReaderPostTableWrapper
import org.wordpress.android.models.ReaderPost
import org.wordpress.android.models.ReaderTagType.FOLLOWED
import org.wordpress.android.modules.IO_THREAD
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostsByTag
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowRelatedPostDetails
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowSitePickerForResult
import org.wordpress.android.ui.reader.discover.ReaderPostActions
import org.wordpress.android.ui.reader.discover.ReaderPostCardAction.SecondaryAction
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionType.FOLLOW
import org.wordpress.android.ui.reader.discover.ReaderPostCardActionsHandler
import org.wordpress.android.ui.reader.discover.ReaderPostMoreButtonUiStateBuilder
import org.wordpress.android.ui.reader.models.ReaderSimplePost
import org.wordpress.android.ui.reader.models.ReaderSimplePostList
import org.wordpress.android.ui.reader.reblog.ReblogUseCase
import org.wordpress.android.ui.reader.usecases.ReaderFetchRelatedPostsUseCase
import org.wordpress.android.ui.reader.usecases.ReaderFetchRelatedPostsUseCase.FetchRelatedPostsState
import org.wordpress.android.ui.reader.usecases.ReaderSiteFollowUseCase.FollowSiteState.FollowStatusChanged
import org.wordpress.android.ui.reader.utils.ReaderUtilsWrapper
import org.wordpress.android.ui.reader.views.uistates.FollowButtonUiState
import org.wordpress.android.ui.reader.views.uistates.ReaderPostDetailsHeaderViewUiState.ReaderPostDetailsHeaderUiState
import org.wordpress.android.ui.utils.UiDimen
import org.wordpress.android.ui.utils.UiString
import org.wordpress.android.ui.utils.UiString.UiStringRes
import org.wordpress.android.util.EventBusWrapper
import org.wordpress.android.viewmodel.Event
import org.wordpress.android.viewmodel.ScopedViewModel
import javax.inject.Inject
import javax.inject.Named

class ReaderPostDetailViewModel @Inject constructor(
    private val readerPostCardActionsHandler: ReaderPostCardActionsHandler,
    private val readerUtilsWrapper: ReaderUtilsWrapper,
    private val readerPostTableWrapper: ReaderPostTableWrapper,
    private val readerPostMoreButtonUiStateBuilder: ReaderPostMoreButtonUiStateBuilder,
    private val postDetailUiStateBuilder: ReaderPostDetailUiStateBuilder,
    private val reblogUseCase: ReblogUseCase,
    private val readerFetchRelatedPostsUseCase: ReaderFetchRelatedPostsUseCase,
    private val eventBusWrapper: EventBusWrapper,
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher,
    @Named(IO_THREAD) private val ioDispatcher: CoroutineDispatcher
) : ScopedViewModel(mainDispatcher) {
    private val _uiState = MediatorLiveData<ReaderPostDetailsUiState>()
    val uiState: LiveData<ReaderPostDetailsUiState> = _uiState

    private val _refreshPost = MediatorLiveData<Event<Unit>>()
    val refreshPost: LiveData<Event<Unit>> = _refreshPost

    private val _navigationEvents = MediatorLiveData<Event<ReaderNavigationEvents>>()
    val navigationEvents: LiveData<Event<ReaderNavigationEvents>> = _navigationEvents

    private val _snackbarEvents = MediatorLiveData<Event<SnackbarMessageHolder>>()
    val snackbarEvents: LiveData<Event<SnackbarMessageHolder>> = _snackbarEvents

    /**
     * Post which is about to be reblogged after the user selects a target site.
     */
    private var pendingReblogPost: ReaderPost? = null

    private var isStarted = false

    init {
        eventBusWrapper.register(readerFetchRelatedPostsUseCase)
    }

    fun start() {
        if (isStarted) {
            return
        }
        isStarted = true

        init()
    }

    private fun init() {
        readerPostCardActionsHandler.initScope(this)
        _uiState.addSource(readerPostCardActionsHandler.followStatusUpdated) { followStatusChanged ->
            val currentUiState: ReaderPostDetailsUiState? = _uiState.value

            currentUiState?.let {
                findPost(currentUiState.postId, currentUiState.blogId)?.let { post ->
                    post.isFollowedByCurrentUser = followStatusChanged.following
                    updateSelectedPostFollowButton(
                            currentUiState = currentUiState,
                            isFollowed = post.isFollowedByCurrentUser
                    )
                }

                updateGlobalRelatedPostsFollowButton(
                        currentUiState = currentUiState,
                        followStatusChanged = followStatusChanged
                )
            }
        }

        _refreshPost.addSource(readerPostCardActionsHandler.refreshPosts) {
            val currentUiState: ReaderPostDetailsUiState? = _uiState.value
            currentUiState?.let {
                findPost(currentUiState.postId, currentUiState.blogId)?.let { post ->
                    updatePostActions(post)
                }
            }
        }

        _snackbarEvents.addSource(readerPostCardActionsHandler.snackbarEvents) { event ->
            _snackbarEvents.value = event
        }

        _navigationEvents.addSource(readerPostCardActionsHandler.navigationEvents) { event ->
            val target = event.peekContent()
            if (target is ShowSitePickerForResult) {
                pendingReblogPost = target.post
            }
            _navigationEvents.value = event
        }
    }

    fun onMoreButtonClicked() {
        changeMoreMenuVisibility(true)
    }

    fun onMoreMenuDismissed() {
        changeMoreMenuVisibility(false)
    }

    fun onMoreMenuItemClicked(type: ReaderPostCardActionType) {
        val currentUiState = uiState.value
        currentUiState?.let {
            onButtonClicked(currentUiState.postId, currentUiState.blogId, type)
        }
        changeMoreMenuVisibility(false)
    }

    private fun changeMoreMenuVisibility(show: Boolean) {
        val currentUiState = uiState.value
        currentUiState?.let {
            findPost(it.postId, it.blogId)?.let { post ->
                val moreMenuItems = if (show) {
                    readerPostMoreButtonUiStateBuilder.buildMoreMenuItemsBlocking(
                            post, this@ReaderPostDetailViewModel::onButtonClicked
                    )
                } else {
                    null
                }

                _uiState.value = it.copy(moreMenuItems = moreMenuItems)
            }
        }
    }

    fun onButtonClicked(postId: Long, blogId: Long, type: ReaderPostCardActionType) {
        launch {
            findPost(postId, blogId)?.let {
                readerPostCardActionsHandler.onAction(it, type, isBookmarkList = false, fromPostDetails = true)
            }
        }
    }

    fun onReblogSiteSelected(siteLocalId: Int) {
        launch {
            val state = reblogUseCase.onReblogSiteSelected(siteLocalId, pendingReblogPost)
            val navigationTarget = reblogUseCase.convertReblogStateToNavigationEvent(state)
            if (navigationTarget != null) {
                _navigationEvents.value = Event(navigationTarget)
            } else {
                _snackbarEvents.value = Event(SnackbarMessageHolder(UiStringRes(R.string.reader_reblog_error)))
            }
            pendingReblogPost = null
        }
    }

    fun onShowPost(post: ReaderPost) {
        _uiState.value = convertPostToUiState(post)
    }

    fun onUpdatePost(post: ReaderPost) {
        _uiState.value = convertPostToUiState(post)
    }

    private fun onTagItemClicked(tagSlug: String) {
        launch(ioDispatcher) {
            val readerTag = readerUtilsWrapper.getTagFromTagName(tagSlug, FOLLOWED)
            _navigationEvents.postValue(Event(ShowPostsByTag(readerTag)))
        }
    }

    private fun onBlogSectionClicked(postId: Long, blogId: Long) {
        launch {
            findPost(postId, blogId)?.let {
                readerPostCardActionsHandler.handleHeaderClicked(blogId, it.feedId)
            }
        }
    }

    private fun onRelatedPostItemClicked(postId: Long, blogId: Long, isGlobal: Boolean) {
        _navigationEvents.value = Event(ShowRelatedPostDetails(postId = postId, blogId = blogId, isGlobal = isGlobal))
    }

    private fun onRelatedPostFollowClicked(relatedPost: ReaderSimplePost) {
        launch {
            readerPostCardActionsHandler.onFollowRelatedPostBlog(
                    blogId = relatedPost.siteId,
                    siteName = relatedPost.siteName
            )
        }
    }

    fun onRelatedPostsRequested(sourcePost: ReaderPost) {
        /* Related posts only available for wp.com */
        if (!sourcePost.isWP) return

        launch {
            when (val fetchRelatedPostsState = readerFetchRelatedPostsUseCase.fetchRelatedPosts(sourcePost)) {
                is FetchRelatedPostsState.AlreadyRunning,
                is FetchRelatedPostsState.Failed.NoNetwork,
                is FetchRelatedPostsState.Failed.RequestFailed -> Unit // Do Nothing

                is FetchRelatedPostsState.Success -> updateRelatedPostsUiState(sourcePost, fetchRelatedPostsState)
            }
        }
    }

    private fun findPost(postId: Long, blogId: Long): ReaderPost? {
        return readerPostTableWrapper.getBlogPost(
                blogId,
                postId,
                true
        )
    }

    private fun convertPostToUiState(
        post: ReaderPost
    ): ReaderPostDetailsUiState {
        return postDetailUiStateBuilder.mapPostToUiState(
                post = post,
                onButtonClicked = this@ReaderPostDetailViewModel::onButtonClicked,
                onBlogSectionClicked = this@ReaderPostDetailViewModel::onBlogSectionClicked,
                onFollowClicked = { onButtonClicked(post.postId, post.blogId, FOLLOW) },
                onTagItemClicked = this@ReaderPostDetailViewModel::onTagItemClicked
        )
    }

    private fun convertRelatedPostsToUiState(
        sourcePost: ReaderPost,
        relatedPosts: ReaderSimplePostList,
        isGlobal: Boolean
    ) = postDetailUiStateBuilder.mapRelatedPostsToUiState(
            sourcePost = sourcePost,
            relatedPosts = relatedPosts,
            isGlobal = isGlobal,
            onItemClicked = this@ReaderPostDetailViewModel::onRelatedPostItemClicked
    )

    private fun updateSelectedPostFollowButton(
        currentUiState: ReaderPostDetailsUiState,
        isFollowed: Boolean
    ) {
        val updatedFollowButtonUiState = currentUiState
                .headerUiState
                .followButtonUiState
                .copy(isFollowed = isFollowed)

        val updatedHeaderUiState = currentUiState
                .headerUiState
                .copy(followButtonUiState = updatedFollowButtonUiState)

        _uiState.value = currentUiState.copy(headerUiState = updatedHeaderUiState)
    }

    private fun updateGlobalRelatedPostsFollowButton(
        currentUiState: ReaderPostDetailsUiState,
        followStatusChanged: FollowStatusChanged
    ) {
        val updatedGlobalRelatedPosts = currentUiState.globalRelatedPosts?.copy(
                cards = currentUiState.globalRelatedPosts.cards?.map { post ->
                    if (post.blogId == followStatusChanged.blogId) {
                        val updatedFollowState = post.followButtonUiState
                                ?.copy(isFollowed = followStatusChanged.following)
                        post.copy(followButtonUiState = updatedFollowState)
                    } else post
                }
        )
        _uiState.value = currentUiState.copy(globalRelatedPosts = updatedGlobalRelatedPosts)
    }

    private fun updatePostActions(post: ReaderPost) {
        _uiState.value = _uiState.value?.copy(
                actions = postDetailUiStateBuilder.buildPostActions(
                        post,
                        this@ReaderPostDetailViewModel::onButtonClicked
                )
        )
    }

    private fun updateRelatedPostsUiState(sourcePost: ReaderPost, state: FetchRelatedPostsState.Success) {
        _uiState.value = _uiState.value?.copy(
                localRelatedPosts = convertRelatedPostsToUiState(
                        sourcePost = sourcePost,
                        relatedPosts = state.localRelatedPosts,
                        isGlobal = false
                ),
                globalRelatedPosts = convertRelatedPostsToUiState(
                        sourcePost = sourcePost,
                        relatedPosts = state.globalRelatedPosts,
                        isGlobal = true
                )
        )
    }

    data class ReaderPostDetailsUiState(
        val postId: Long,
        val blogId: Long,
        val headerUiState: ReaderPostDetailsHeaderUiState,
        val moreMenuItems: List<SecondaryAction>? = null,
        val actions: ReaderPostActions,
        val localRelatedPosts: RelatedPostsUiState? = null,
        val globalRelatedPosts: RelatedPostsUiState? = null
    ) {
        data class RelatedPostsUiState(
            val cards: List<ReaderRelatedPostUiState>?,
            val isGlobal: Boolean,
            val siteName: String?
        ) {
            data class ReaderRelatedPostUiState(
                val postId: Long,
                val blogId: Long,
                val isGlobal: Boolean,
                val title: UiString?,
                val featuredImageUrl: String?,
                val featuredImageCornerRadius: UiDimen,
                val followButtonUiState: FollowButtonUiState?,
                val onItemClicked: (Long, Long, Boolean) -> Unit
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        readerPostCardActionsHandler.onCleared()
        eventBusWrapper.unregister(readerFetchRelatedPostsUseCase)
    }
}
