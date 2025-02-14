package org.wordpress.android.ui.reader.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.datasets.ReaderCommentTable;
import org.wordpress.android.datasets.ReaderDatabase;
import org.wordpress.android.datasets.ReaderLikeTable;
import org.wordpress.android.datasets.ReaderUserTable;
import org.wordpress.android.models.ReaderComment;
import org.wordpress.android.models.ReaderCommentList;
import org.wordpress.android.models.ReaderUserList;
import org.wordpress.android.ui.reader.ReaderConstants;
import org.wordpress.android.ui.reader.ReaderEvents;
import org.wordpress.android.ui.reader.ReaderEvents.UpdateCommentsScenario;
import org.wordpress.android.ui.reader.actions.ReaderActions;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResult;
import org.wordpress.android.ui.reader.actions.ReaderActions.UpdateResultListener;
import org.wordpress.android.util.AppLog;
import org.wordpress.android.util.JSONUtils;

import static org.wordpress.android.ui.reader.ReaderEvents.UpdateCommentsScenario.COMMENT_SNIPPET;
import static org.wordpress.android.ui.reader.ReaderEvents.UpdateCommentsScenario.GENERIC;

public class ReaderCommentService extends Service {
    private static final String ARG_POST_ID = "post_id";
    private static final String ARG_BLOG_ID = "blog_id";
    private static final String ARG_COMMENT_ID = "comment_id";
    private static final String ARG_PAGE_INFO = "page_info";

    private enum PageInfo {
        FIRST_PAGE,
        NEXT_PAGE,
        COMMENTS_SNIPPET_PAGE;
    }

    private static int mCurrentPage;

    public static void startService(Context context, long blogId, long postId, boolean requestNextPage) {
        if (context == null) {
            return;
        }

        Intent intent = new Intent(context, ReaderCommentService.class);
        intent.putExtra(ARG_BLOG_ID, blogId);
        intent.putExtra(ARG_POST_ID, postId);
        intent.putExtra(ARG_PAGE_INFO, requestNextPage ? PageInfo.NEXT_PAGE : PageInfo.FIRST_PAGE);
        context.startService(intent);
    }

    // Requests comments until the passed commentId is found
    public static void startServiceForComment(Context context, long blogId, long postId, long commentId) {
        if (context == null) {
            return;
        }

        Intent intent = new Intent(context, ReaderCommentService.class);
        intent.putExtra(ARG_BLOG_ID, blogId);
        intent.putExtra(ARG_POST_ID, postId);
        intent.putExtra(ARG_COMMENT_ID, commentId);
        intent.putExtra(ARG_PAGE_INFO, PageInfo.FIRST_PAGE);
        context.startService(intent);
    }

    public static void startServiceForCommentSnippet(Context context, long blogId, long postId) {
        if (context == null) {
            return;
        }

        Intent intent = new Intent(context, ReaderCommentService.class);
        intent.putExtra(ARG_BLOG_ID, blogId);
        intent.putExtra(ARG_POST_ID, postId);
        intent.putExtra(ARG_PAGE_INFO, PageInfo.COMMENTS_SNIPPET_PAGE);
        context.startService(intent);
    }

    public static void stopService(Context context) {
        if (context == null) {
            return;
        }

        Intent intent = new Intent(context, ReaderCommentService.class);
        context.stopService(intent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.i(AppLog.T.READER, "reader comment service > created");
    }

    @Override
    public void onDestroy() {
        AppLog.i(AppLog.T.READER, "reader comment service > destroyed");
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        final long blogId = intent.getLongExtra(ARG_BLOG_ID, 0);
        final long postId = intent.getLongExtra(ARG_POST_ID, 0);
        final long commentId = intent.getLongExtra(ARG_COMMENT_ID, 0);
        PageInfo pageInfo = (PageInfo) intent.getSerializableExtra(ARG_PAGE_INFO);

        UpdateCommentsScenario commentsScenario = pageInfo == PageInfo.COMMENTS_SNIPPET_PAGE
                ? COMMENT_SNIPPET
                : GENERIC;

        EventBus.getDefault().post(new ReaderEvents.UpdateCommentsStarted(commentsScenario, blogId, postId));

        final int commentsToRequest = pageInfo == PageInfo.COMMENTS_SNIPPET_PAGE
                ? ReaderConstants.READER_COMMENTS_TO_REQUEST_FOR_POST_SNIPPET
                : ReaderConstants.READER_MAX_COMMENTS_TO_REQUEST;

        switch (pageInfo) {
            case NEXT_PAGE:
                int prevPage = ReaderCommentTable.getLastPageNumberForPost(blogId, postId);
                mCurrentPage = prevPage + 1;
                break;
            case FIRST_PAGE:
            case COMMENTS_SNIPPET_PAGE:
            default:
                mCurrentPage = 1;
                break;
        }

        updateCommentsForPost(blogId, postId, mCurrentPage, commentsToRequest, new UpdateResultListener() {
            @Override
            public void onUpdateResult(UpdateResult result) {
                if (commentId > 0) {
                    if (ReaderCommentTable.commentExists(blogId, postId, commentId) || !result.isNewOrChanged()) {
                        EventBus.getDefault().post(new ReaderEvents.UpdateCommentsEnded(
                                result,
                                commentsScenario,
                                blogId,
                                postId
                        ));
                        stopSelf();
                    } else {
                        // Comment not found yet, request the next page
                        mCurrentPage++;
                        updateCommentsForPost(blogId, postId, mCurrentPage, commentsToRequest, this);
                    }
                } else {
                    EventBus.getDefault().post(new ReaderEvents.UpdateCommentsEnded(
                            result,
                            commentsScenario,
                            blogId,
                            postId
                    ));
                    stopSelf();
                }
            }
        });

        return START_NOT_STICKY;
    }

    private static void updateCommentsForPost(final long blogId,
                                              final long postId,
                                              final int pageNumber,
                                              final int commentsToRequest,
                                              final ReaderActions.UpdateResultListener resultListener) {
        String path = "sites/" + blogId + "/posts/" + postId + "/replies/"
                      + "?number=" + Integer.toString(commentsToRequest)
                      + "&meta=likes"
                      + "&force=wpcom"
                      + "&hierarchical=true"
                      + "&order=ASC"
                      + "&page=" + pageNumber;

        RestRequest.Listener listener = new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject jsonObject) {
                handleUpdateCommentsResponse(jsonObject, blogId, postId, pageNumber, resultListener);
            }
        };
        RestRequest.ErrorListener errorListener = new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.READER, volleyError);
                resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            }
        };
        AppLog.d(AppLog.T.READER, "updating comments");
        WordPress.getRestClientUtilsV1_1().get(path, null, null, listener, errorListener);
    }

    private static void handleUpdateCommentsResponse(final JSONObject jsonObject,
                                                     final long blogId,
                                                     final long postId,
                                                     final int pageNumber,
                                                     final ReaderActions.UpdateResultListener resultListener) {
        if (jsonObject == null) {
            resultListener.onUpdateResult(ReaderActions.UpdateResult.FAILED);
            return;
        }

        new Thread() {
            @Override
            public void run() {
                final boolean hasNewComments;

                ReaderDatabase.getWritableDb().beginTransaction();
                try {
                    // purge existing comments if this was a request for the first page of comments
                    if (pageNumber == 1) {
                        ReaderCommentTable.purgeCommentsForPost(blogId, postId);
                    }

                    ReaderCommentList serverComments = new ReaderCommentList();
                    JSONArray jsonCommentList = jsonObject.optJSONArray("comments");
                    if (jsonCommentList != null) {
                        for (int i = 0; i < jsonCommentList.length(); i++) {
                            JSONObject jsonComment = jsonCommentList.optJSONObject(i);

                            // extract this comment and add it to the list
                            ReaderComment comment = ReaderComment.fromJson(jsonComment, blogId);
                            comment.pageNumber = pageNumber;
                            serverComments.add(comment);

                            // extract and save likes for this comment
                            JSONObject jsonLikes = JSONUtils.getJSONChild(jsonComment, "meta/data/likes");
                            if (jsonLikes != null) {
                                ReaderUserList likingUsers = ReaderUserList.fromJsonLikes(jsonLikes);
                                ReaderUserTable.addOrUpdateUsers(likingUsers);
                                ReaderLikeTable.setLikesForComment(comment, likingUsers.getUserIds());
                            }
                        }
                    }

                    hasNewComments = (serverComments.size() > 0);

                    // save to db regardless of whether any are new so changes to likes are stored
                    ReaderCommentTable.addOrUpdateComments(serverComments);
                    ReaderDatabase.getWritableDb().setTransactionSuccessful();
                } finally {
                    ReaderDatabase.getWritableDb().endTransaction();
                }

                ReaderActions.UpdateResult result =
                        (hasNewComments ? ReaderActions.UpdateResult.HAS_NEW : ReaderActions.UpdateResult.UNCHANGED);
                resultListener.onUpdateResult(result);
            }
        }.start();
    }
}
