package org.wordpress.android.ui.reader.discover

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.android.synthetic.main.reader_discover_fragment_layout.*
import kotlinx.android.synthetic.main.reader_fullscreen_error_with_retry.*
import org.wordpress.android.R
import org.wordpress.android.R.dimen
import org.wordpress.android.WordPress
import org.wordpress.android.ui.ActivityLauncher
import org.wordpress.android.ui.RequestCodes
import org.wordpress.android.ui.main.SitePickerActivity
import org.wordpress.android.ui.main.WPMainActivity
import org.wordpress.android.ui.pages.SnackbarMessageHolder
import org.wordpress.android.ui.reader.ReaderActivityLauncher
import org.wordpress.android.ui.reader.ReaderPostWebViewCachingFragment
import org.wordpress.android.ui.reader.discover.ReaderDiscoverViewModel.DiscoverUiState.ContentUiState
import org.wordpress.android.ui.reader.discover.ReaderDiscoverViewModel.DiscoverUiState.ErrorUiState
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.OpenEditorForReblog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.OpenPost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.SharePost
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBlogPreview
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedSavedOnlyLocallyDialog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowBookmarkedTab
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowNoSitesToReblog
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostDetail
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowPostsByTag
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowReaderComments
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowSitePickerForResult
import org.wordpress.android.ui.reader.discover.ReaderNavigationEvents.ShowVideoViewer
import org.wordpress.android.ui.reader.usecases.PreLoadPostContent
import org.wordpress.android.ui.utils.UiHelpers
import org.wordpress.android.util.WPSwipeToRefreshHelper
import org.wordpress.android.util.analytics.AnalyticsTrackerWrapper
import org.wordpress.android.util.image.ImageManager
import org.wordpress.android.widgets.RecyclerItemDecoration
import org.wordpress.android.widgets.WPSnackbar
import javax.inject.Inject

class ReaderDiscoverFragment : Fragment(R.layout.reader_discover_fragment_layout) {
    private var bookmarksSavedLocallyDialog: AlertDialog? = null
    @Inject lateinit var viewModelFactory: ViewModelProvider.Factory
    @Inject lateinit var uiHelpers: UiHelpers
    @Inject lateinit var imageManager: ImageManager
    private lateinit var viewModel: ReaderDiscoverViewModel
    @Inject lateinit var analyticsTrackerWrapper: AnalyticsTrackerWrapper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (requireActivity().application as WordPress).component().inject(this)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        initViewModel()
    }

    private fun setupViews() {
        recycler_view.layoutManager = LinearLayoutManager(context, RecyclerView.VERTICAL, false)
        recycler_view.adapter = ReaderDiscoverAdapter(uiHelpers, imageManager)

        val spacingHorizontal = resources.getDimensionPixelSize(dimen.reader_card_margin)
        val spacingVertical = resources.getDimensionPixelSize(dimen.reader_card_gutters)
        recycler_view.addItemDecoration(RecyclerItemDecoration(spacingHorizontal, spacingVertical, false))

        WPSwipeToRefreshHelper.buildSwipeToRefreshHelper(ptr_layout) {
            viewModel.swipeToRefresh()
        }
        error_retry.setOnClickListener {
            viewModel.onRetryButtonClick()
        }
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(this, viewModelFactory).get(ReaderDiscoverViewModel::class.java)
        viewModel.uiState.observe(viewLifecycleOwner, Observer {
            when (it) {
                is ContentUiState -> {
                    (recycler_view.adapter as ReaderDiscoverAdapter).update(it.cards)
                }
                is ErrorUiState -> {
                    uiHelpers.setTextOrHide(error_title, it.titleResId)
                }
            }
            uiHelpers.updateVisibility(recycler_view, it.contentVisiblity)
            uiHelpers.updateVisibility(progress_bar, it.fullscreenProgressVisibility)
            uiHelpers.updateVisibility(progress_text, it.fullscreenProgressVisibility)
            uiHelpers.updateVisibility(error_layout, it.fullscreenErrorVisibility)
            uiHelpers.updateVisibility(progress_loading_more, it.loadMoreProgressVisibility)
            ptr_layout.isEnabled = it.swipeToRefreshEnabled
            ptr_layout.isRefreshing = it.reloadProgressVisibility
        })
        viewModel.navigationEvents.observe(viewLifecycleOwner, Observer {
            it.applyIfNotHandled {
                when (this) {
                    is ShowPostDetail -> ReaderActivityLauncher.showReaderPostDetail(context, post.blogId, post.postId)
                    is SharePost -> ReaderActivityLauncher.sharePost(context, post)
                    is OpenPost -> ReaderActivityLauncher.openPost(context, post)
                    is ShowReaderComments -> ReaderActivityLauncher.showReaderComments(context, blogId, postId)
                    is ShowNoSitesToReblog -> ReaderActivityLauncher.showNoSiteToReblog(activity)
                    is ShowSitePickerForResult -> ActivityLauncher
                            .showSitePickerForResult(this@ReaderDiscoverFragment, this.site, this.mode)
                    is OpenEditorForReblog -> ActivityLauncher
                            .openEditorForReblog(activity, this.site, this.post, this.source)
                    is ShowBookmarkedTab -> {
                        ActivityLauncher.viewSavedPostsListInReader(activity)
                        if (requireActivity() is WPMainActivity) {
                            requireActivity().overridePendingTransition(0, 0)
                        }
                    }
                    is ShowBookmarkedSavedOnlyLocallyDialog -> showBookmarkSavedLocallyDialog(this)
                    is ShowPostsByTag -> ReaderActivityLauncher.showReaderTagPreview(context, this.tag)
                    is ShowVideoViewer -> ReaderActivityLauncher.showReaderVideoViewer(context, this.videoUrl)
                    is ShowBlogPreview -> ReaderActivityLauncher.showReaderBlogOrFeedPreview(
                            context,
                            this.siteId,
                            this.feedId
                    )
                }
            }
        })
        viewModel.snackbarEvents.observe(viewLifecycleOwner, Observer {
            it?.applyIfNotHandled {
                showSnackbar()
            }
        })
        viewModel.preloadPostEvents.observe(viewLifecycleOwner, Observer {
            it?.applyIfNotHandled {
                addWebViewCachingFragment()
            }
        })
        viewModel.start()
    }

    private fun showBookmarkSavedLocallyDialog(bookmarkDialog: ShowBookmarkedSavedOnlyLocallyDialog) {
        if (bookmarksSavedLocallyDialog == null) {
            MaterialAlertDialogBuilder(requireActivity())
                    .setTitle(getString(bookmarkDialog.title))
                    .setMessage(getString(bookmarkDialog.message))
                    .setPositiveButton(getString(bookmarkDialog.buttonLabel)) { _, _ ->
                        bookmarkDialog.okButtonAction.invoke()
                    }
                    .setOnDismissListener {
                        bookmarksSavedLocallyDialog = null
                    }
                    .setCancelable(false)
                    .create()
                    .let {
                        bookmarksSavedLocallyDialog = it
                        it.show()
                    }
        }
    }

    private fun SnackbarMessageHolder.showSnackbar() {
        val snackbar = WPSnackbar.make(
                constraint_layout,
                uiHelpers.getTextOfUiString(requireContext(), this.message),
                Snackbar.LENGTH_LONG
        )
        if (this.buttonTitle != null) {
            snackbar.setAction(uiHelpers.getTextOfUiString(requireContext(), this.buttonTitle)) {
                this.buttonAction.invoke()
            }
        }
        snackbar.show()
    }

    private fun PreLoadPostContent.addWebViewCachingFragment() {
        val tag = "$blogId$postId"

        if (parentFragmentManager.findFragmentByTag(tag) == null) {
            parentFragmentManager.beginTransaction()
                    .add(ReaderPostWebViewCachingFragment.newInstance(blogId, postId), tag)
                    .commit()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bookmarksSavedLocallyDialog?.dismiss()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RequestCodes.SITE_PICKER && resultCode == Activity.RESULT_OK && data != null) {
            val siteLocalId = data.getIntExtra(SitePickerActivity.KEY_LOCAL_ID, -1)
            viewModel.onReblogSiteSelected(siteLocalId)
        }
    }
}
