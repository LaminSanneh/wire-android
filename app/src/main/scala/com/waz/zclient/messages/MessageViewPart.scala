/**
  * Wire
  * Copyright (C) 2017 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.messages

import android.content.Context
import android.graphics.{Canvas, Paint}
import android.text.format.DateFormat
import android.util.AttributeSet
import android.view.View
import android.widget.{LinearLayout, RelativeLayout}
import com.waz.ZLog.ImplicitTag._
import com.waz.api.Message
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.Threading
import com.waz.utils.events.{EventStream, Signal}
import com.waz.zclient.common.views.ChatheadView
import com.waz.zclient.controllers.global.AccentColorController
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.parts.EphemeralDotsDrawable
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceTextView}
import com.waz.zclient.ui.theme.ThemeUtils
import com.waz.zclient.utils.ContextUtils.{getColor, getDimenPx}
import com.waz.zclient.utils.ZTimeFormatter.getSeparatorTime
import com.waz.zclient.utils._
import com.waz.zclient.{R, ViewHelper}
import org.threeten.bp.{Instant, LocalDateTime, ZoneId}


trait MessageViewPart extends View {
  val tpe: MsgPart
  protected val messageAndLikes = Signal[MessageAndLikes]()
  protected val message = messageAndLikes.map(_.message)
  message.disableAutowiring() //important to ensure the signal keeps updating itself in the absence of any listeners

  def set(msg: MessageAndLikes, part: Option[MessageContent], opts: MsgBindOptions): Unit = {
    messageAndLikes.publish(msg, Threading.Ui)
  }

  //By default disable clicks for all view types. There are fewer that need click functionality than those that don't
  this.onClick {}
  this.onLongClick(false)
}

//Marker for views that should hide/display the footer when clicked and show the menu when long clicked.
trait ClickableViewPart extends MessageViewPart with ViewHelper {
  import ClickableViewPart._
  import com.waz.threading.Threading.Implicits.Ui
  val zms = inject[Signal[ZMessaging]]
  val reactions = zms.map(_.reactions)
  val onClicked = EventStream[Unit]()

  private val likedByMe = messageAndLikes map { m => m.likedBySelf }

  def onSingleClick() = {
    onClicked ! ({})
    getParent.asInstanceOf[View].performClick()
  }

  def onDoubleClick() = {
    for {
      msg <- message.currentValue
      if isLikeable(msg.msgType)
      reacts <- reactions.head
      likedByMe <- likedByMe.currentValue
    } {
      if (likedByMe) reacts.unlike(msg.convId, msg.id)
      else reacts.like(msg.convId, msg.id)
      getParent.asInstanceOf[View].performClick() //perform click to change focus
    }
  }

  this.onClick ({ onSingleClick }, { onDoubleClick })

  this.onLongClick(getParent.asInstanceOf[View].performLongClick())
}

object ClickableViewPart {
  import Message.Type._
  val likableTypes = Set(
    ANY_ASSET,
    ASSET,
    AUDIO_ASSET,
    LOCATION,
    TEXT,
    TEXT_EMOJI_ONLY,
    RICH_MEDIA,
    VIDEO_ASSET
  )

  def isLikeable(mt: Message.Type) = likableTypes.contains(mt)
}

// Marker for view parts that should be laid out as in FrameLayout (instead of LinearLayout)
trait FrameLayoutPart extends MessageViewPart

trait TimeSeparator extends MessageViewPart with ViewHelper {

  val is24HourFormat = DateFormat.is24HourFormat(getContext)

  lazy val timeText: TypefaceTextView = findById(R.id.separator__time)
  lazy val unreadDot: UnreadDot = findById(R.id.unread_dot)

  val time = Signal[Instant]()
  val text = time map { t =>
    getSeparatorTime(getContext.getResources, LocalDateTime.now, DateConvertUtils.asLocalDateTime(t), is24HourFormat, ZoneId.systemDefault, true)
  }

  text.on(Threading.Ui)(timeText.setTransformedText)

  override def set(msg: MessageAndLikes, part: Option[MessageContent], opts: MsgBindOptions): Unit = {
    super.set(msg, part, opts)
    this.time ! msg.message.time
    unreadDot.show ! opts.isFirstUnread
  }
}

class SeparatorView(context: Context, attrs: AttributeSet, style: Int) extends RelativeLayout(context, attrs, style) with TimeSeparator {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  override val tpe: MsgPart = MsgPart.Separator
}

class SeparatorViewLarge(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with TimeSeparator {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  override val tpe: MsgPart = MsgPart.SeparatorLarge

  if (ThemeUtils.isDarkTheme(context)) setBackgroundColor(getColor(R.color.white_8))
  else setBackgroundColor(getColor(R.color.black_4))

}

class UnreadDot(context: Context, attrs: AttributeSet, style: Int) extends View(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  val accent = inject[AccentColorController].accentColor
  val show = Signal[Boolean](false)

  val dotRadius = getDimenPx(R.dimen.conversation__unread_dot__radius)
  val dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG)

  accent { color =>
    dotPaint.setColor(color.getColor())
    invalidate()
  }

  show.onChanged.on(Threading.Ui)(_ => invalidate())

  override def onDraw(canvas: Canvas): Unit = if (show.currentValue.getOrElse(false)) canvas.drawCircle(getWidth / 2, getHeight / 2, dotRadius, dotPaint)
}

class UserPartView(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with MessageViewPart with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

  def this(context: Context) = this(context, null, 0)

  override val tpe: MsgPart = MsgPart.User

  inflate(R.layout.message_user_content)

  private val chathead: ChatheadView = findById(R.id.chathead)
  private val tvName: TypefaceTextView = findById(R.id.tvName)
  private val tvStateGlyph: GlyphTextView = findById(R.id.gtvStateGlyph)

  private val zms = inject[Signal[ZMessaging]]
  private val userId = Signal[UserId]()

  private val user = Signal(zms, userId).flatMap {
    case (z, id) => z.usersStorage.signal(id)
  }

  private val stateGlyph = message map {
    case m if m.msgType == Message.Type.RECALLED => Some(R.string.glyph__trash)
    case m if m.editTime != Instant.EPOCH => Some(R.string.glyph__edit)
    case _ => None
  }

  userId(chathead.setUserId)

  user.map(_.getDisplayName).on(Threading.Ui)(tvName.setTransformedText)

  stateGlyph.map(_.isDefined) { tvStateGlyph.setVisible }

  stateGlyph.collect { case Some(glyph) => glyph } { tvStateGlyph.setText }

  override def set(msg: MessageAndLikes, part: Option[MessageContent], opts: MsgBindOptions): Unit = {
    super.set(msg, part, opts)
    userId ! msg.message.userId
  }
}

class EmptyPartView(context: Context, attrs: AttributeSet, style: Int) extends View(context, attrs, style) with MessageViewPart {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  override val tpe = MsgPart.Empty
}

class EphemeralDotsView(context: Context, attrs: AttributeSet, style: Int) extends View(context, attrs, style) with ViewHelper with FrameLayoutPart {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  override val tpe = MsgPart.EphemeralDots
  val background = new EphemeralDotsDrawable()

  setBackground(background)

  override def set(msg: MessageAndLikes, part: Option[MessageContent], opts: MsgBindOptions): Unit = {
    super.set(msg, part, opts)
    background.setMessage(msg.message.id)
  }
}