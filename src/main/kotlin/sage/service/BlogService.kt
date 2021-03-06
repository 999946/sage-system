package sage.service

import com.avaje.ebean.Ebean
import org.markdown4j.Markdown4jProcessor
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import sage.domain.cache.GlobalCaches
import sage.domain.commons.*
import sage.domain.permission.CheckPermission
import sage.entity.*
import sage.transfer.BlogView
import sage.util.JsoupUtil
import java.sql.Timestamp
import java.util.*

@Service
class BlogService
@Autowired constructor(
    private val tweetPostService: TweetPostService,
    private val userService: UserService,
    private val searchService: SearchService,
    private val notifService: NotificationService) {

  fun post(userId: Long, title: String, inputContent: String, tagIds: Set<Long>, contentType: String): Blog {
    checkLength(title, inputContent)

    val blog = Blog(title, inputContent, "", User.ref(userId), Tag.multiGet(tagIds), Blog.contentTypeValue(contentType))
    val mentionedIds = renderAndGetMentions(blog)
    Ebean.execute {
      blog.save()
      BlogStat(id = blog.id, whenCreated = blog.whenCreated).save()
      tweetPostService.postForBlog(blog)
    }

    userService.updateUserTag(userId, tagIds)

    mentionedIds.forEach { atId -> notifService.mentionedByBlog(atId, userId, blog.id) }

    searchService.index(blog.id, BlogView(blog))
    GlobalCaches.blogsCache.clear()
    return blog
  }

  fun edit(userId: Long, blogId: Long, title: String, inputContent: String, tagIds: Set<Long>, contentType: String): Blog {
    checkLength(title, inputContent)
    val blog = Blog.get(blogId)
    CheckPermission.canEdit(userId, blog, userId == blog.author.id)

    val oldInputContent = blog.inputContent

    blog.title = title
    blog.inputContent = inputContent
    blog.contentType = Blog.contentTypeValue(contentType)
    val mentionedIds = renderAndGetMentions(blog)
    blog.tags = Tag.multiGet(tagIds)
    blog.whenEdited = Timestamp(System.currentTimeMillis())
    blog.update()

    userService.updateUserTag(userId, tagIds)

    if (mentionedIds.isNotEmpty()) {
      val (_, oldMentionedIds) = parseMentions(oldInputContent)
      (mentionedIds - oldMentionedIds).forEach { atId -> notifService.mentionedByBlog(atId, userId, blogId) }
    }

    searchService.index(blog.id, BlogView(blog))
    return blog
  }

  fun delete(userId: Long, blogId: Long) {
    val blog = Blog.get(blogId)
    CheckPermission.canDelete(userId, blog, userId == blog.author.id)

    blog.delete()

    searchService.delete(BlogView::class.java, blog.id)
  }

  fun comment(userId: Long, inputContent: String, blogId: Long, replyUserId: Long?): Comment {
    if (inputContent.isEmpty() || inputContent.length > COMMENT_MAX_LEN) {
      throw BAD_COMMENT_LENGTH
    }
    val (hyperContent, mentionedIds) = ContentParser.comment(inputContent) { name -> User.byName(name) }

    val comment = Comment(inputContent, hyperContent, User.ref(userId), Comment.BLOG, blogId, replyUserId)
    comment.save()
    BlogStat.incComments(blogId)
    TweetStat.incComments(Blog.get(blogId).tweetId)
    
    notifService.commented(Blog.get(blogId).author.id, userId, comment.id)
    if (replyUserId != null) {
      notifService.replied(replyUserId, userId, comment.id)
    }
    mentionedIds.forEach { atId -> notifService.mentionedByComment(atId, userId, comment.id) }
    return comment
  }

  fun hotBlogs() : List<Blog> {
    val stats = BlogStat.where().orderBy("rank desc, id desc").setMaxRows(20).findList()
    return stats.map { Blog.get(it.id) }
  }

  private fun checkLength(title: String, content: String) {
    if (title.isEmpty() || title.length > BLOG_TITLE_MAX_LEN
        || content.isEmpty() || content.length > BLOG_CONTENT_MAX_LEN) {
      throw BAD_INPUT_LENGTH
    }
  }

  fun renderAndGetMentions(blog: Blog) : Set<Long> {
    var content = blog.inputContent

    val pair = parseMentions(content)
    content = pair.first
    val mentionedIds = pair.second

    if (blog.contentType == Blog.MARKDOWN) {
      content = Markdown4jProcessor().process(content)
      content = JsoupUtil.clean(content)
    } else {
      content = JsoupUtil.clean(content)
    }
    blog.content = content
    return mentionedIds
  }

  private fun parseMentions(text: String): Pair<String, Set<Long>> {
    val mentionedIds = HashSet<Long>()
    return ReplaceMention.with {User.byName(it)}.apply(text, mentionedIds) to mentionedIds
  }

  companion object {
    private val BLOG_TITLE_MAX_LEN = 100
    private val BLOG_CONTENT_MAX_LEN = 10000
    private val BAD_INPUT_LENGTH = BadArgumentException(
        "输入长度不正确(标题1~${BLOG_TITLE_MAX_LEN}字,内容1~${BLOG_CONTENT_MAX_LEN}字)")

    private val COMMENT_MAX_LEN = 1000
    private val BAD_COMMENT_LENGTH = BadArgumentException("评论应为1~${COMMENT_MAX_LEN}字")
  }
}
