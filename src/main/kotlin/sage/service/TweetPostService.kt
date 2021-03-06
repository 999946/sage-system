package sage.service

import com.avaje.ebean.Ebean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import sage.domain.cache.GlobalCaches
import sage.domain.commons.*
import sage.domain.permission.CheckPermission
import sage.entity.*
import sage.transfer.MidForwards
import sage.transfer.TweetView
import java.util.*

@Service
class TweetPostService
@Autowired constructor(
    private val searchService: SearchService,
    private val userService: UserService,
    private val transfers: TransferService,
    private val notifService: NotificationService) {

  fun post(userId: Long, inputContent: String, richElements: Collection<RichElement>, tagIds: Collection<Long>): Tweet {
    if (inputContent.isEmpty() || inputContent.length > TWEET_MAX_LEN) {
      throw BAD_TWEET_LENGTH
    }
    val (hyperContent, mentionedIds) = ContentParser.tweet(inputContent) { name -> User.byName(name) }

    val tweet = Tweet(inputContent, hyperContent, richElements, User.ref(userId),
        Tag.multiGet(tagIds))
    Ebean.execute {
      tweet.save()
      TweetStat(id = tweet.id, whenCreated = tweet.whenCreated).save()
    }

    userService.updateUserTag(userId, tagIds)

    mentionedIds.forEach { atId -> notifService.mentionedByTweet(atId, userId, tweet.id) }

    searchService.index(tweet.id, transfers.toTweetViewNoCount(tweet))
    GlobalCaches.tweetsCache.clear()
    return tweet
  }

  /** Should be called in a transaction */
  fun postForBlog(blog: Blog) {
    val tweet = Tweet("", "", emptyList(), User.ref(blog.author.id), blog)
    tweet.save()
    TweetStat(id = tweet.id, whenCreated = tweet.whenCreated).save()
    blog.tweetId = tweet.id
    blog.update()
  }

  fun forward(userId: Long, inputContent: String, originId: Long, removedForwardIds: Collection<Long>): Tweet {
    if (inputContent.length > TWEET_MAX_LEN) {
      throw BAD_TWEET_LENGTH
    }
    val (hyperContent, mentionedIds) = ContentParser.tweet(inputContent) { name -> User.byName(name) }

    val directOrigin = Tweet.ref(originId)
    val origins = fromDirectToInitialOrigin(directOrigin)
    val initialOrigin = origins.last
    val tweet: Tweet
    if (initialOrigin == directOrigin) {
      tweet = Tweet(inputContent, hyperContent, emptyList(), User.ref(userId), initialOrigin)
    } else {
      val midForwards = MidForwards(directOrigin)
      removedForwardIds.forEach { midForwards.removeById(it) }
      tweet = Tweet(inputContent, hyperContent, emptyList(), midForwards, User.ref(userId), initialOrigin)
    }
    Ebean.execute {
      tweet.save()
      TweetStat(id = tweet.id, whenCreated = tweet.whenCreated).save()
    }
    origins.forEach { TweetStat.incForwards(it.id) }

    userService.updateUserTag(userId, tweet.tags.map(Tag::id))

    origins.forEach { origin -> notifService.forwarded(origin.author.id, userId, tweet.id) }
    mentionedIds.forEach { atId -> notifService.mentionedByTweet(atId, userId, tweet.id) }

    searchService.index(tweet.id, transfers.toTweetViewNoCount(tweet))
    GlobalCaches.tweetsCache.clear()
    return tweet
  }

  fun comment(userId: Long, inputContent: String, tweetId: Long, replyUserId: Long?): Comment {
    if (inputContent.isEmpty() || inputContent.length > COMMENT_MAX_LEN) {
      throw BAD_COMMENT_LENGTH
    }
    val (hyperContent, mentionedIds) = ContentParser.comment(inputContent) { name -> User.byName(name) }

    val comment = Comment(inputContent, hyperContent, User.ref(userId), Comment.TWEET, tweetId, replyUserId)
    comment.save()
    TweetStat.incComments(tweetId)
    Tweet.byId(tweetId)?.let(Tweet::blogId)?.let { if (it > 0) {
      BlogStat.incComments(it)
    }}

    notifService.commented(Tweet.ref(tweetId).author.id, userId, comment.id)
    if (replyUserId != null) {
      notifService.replied(replyUserId, userId, comment.id)
    }
    mentionedIds.forEach { atId -> notifService.mentionedByComment(atId, userId, comment.id) }
    return comment
  }

  fun delete(userId: Long, tweetId: Long) {
    val tweet = Tweet.ref(tweetId)
    CheckPermission.canDelete(userId, tweet, userId == tweet.author.id)

    tweet.deleted = true
    tweet.update()

    searchService.delete(TweetView::class.java, tweetId)
  }

  /*
   * Find all nested origins including the direct origin
   */
  private fun fromDirectToInitialOrigin(directOrigin: Tweet): Deque<Tweet> {
    val origins = LinkedList<Tweet>()
    origins.add(directOrigin)
    var origin = Tweet.getOrigin(directOrigin)
    while (origin != null) {
      origins.add(origin)
      origin = Tweet.getOrigin(origin)
    }
    return origins
  }

  companion object {
    private val TWEET_MAX_LEN = 1000
    private val COMMENT_MAX_LEN = 1000
    private val BAD_TWEET_LENGTH = BadArgumentException("状态应为1~1000字")
    private val BAD_COMMENT_LENGTH = BadArgumentException("评论应为1~1000字")
  }
}
