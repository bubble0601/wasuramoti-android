package karuta.hpnpwd.wasuramoti

import android.app.{Activity,AlertDialog}
import android.content.{Intent,Context,DialogInterface}
import android.os.{Bundle,Handler,Build}
import android.support.v7.app.{ActionBarActivity,ActionBar}
import android.util.{Base64,TypedValue}
import android.view.animation.{AnimationUtils,Interpolator}
import android.view.{View,Menu,MenuItem,WindowManager,ViewStub}
import android.widget.{ImageView,Button,RelativeLayout,TextView,LinearLayout,RadioGroup,Toast}

import java.lang.Runnable

import org.json.{JSONTokener,JSONObject,JSONArray}

import scala.collection.mutable

class WasuramotiActivity extends ActionBarActivity with ActivityDebugTrait with MainButtonTrait{
  val MINUTE_MILLISEC = 60000
  var haseo_count = 0
  var release_lock = None:Option[()=>Unit]
  var run_dimlock = None:Option[Runnable]
  var run_refresh_text = None:Option[Runnable]
  val handler = new Handler()
  var bar_poem_info_num = None:Option[Int]

  override def onNewIntent(intent:Intent){
    super.onNewIntent(intent)
    // Since Android core system cannot determine whether or not the new intent is important for us,
    // We have to set intent by our own.
    // We can rely on fact that onResume() is called after onNewIntent()
    setIntent(intent)
  }
  def handleActionView(){
    val intent = getIntent
    // Android 2.x sends same intent at onResume() even after setIntent() is called if resumed from shown list where home button is long pressed.
    // Therefore we check FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY flag to distinguish it.
    if(intent == null ||
      intent.getAction != Intent.ACTION_VIEW ||
      (intent.getFlags & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) > 0
    ){
      return
    }
    val dataString = intent.getDataString
    // we don't need the current intent anymore so replace it with default intent so that getIntent returns plain intent.
    setIntent(new Intent(this,this.getClass))
    dataString.replaceFirst("wasuramoti://","").split("/")(0) match {
      case "fudaset" => importFudaset(dataString)
      case "from_oom" => Utils.messageDialog(this,Right(R.string.from_oom_message))
      case m => Utils.messageDialog(this,Left(s"'${m}' is not correct intent data for ACTION_VIEW for wasuramoti"))
    }
  }

  def reloadFragment(){
    val fragment = WasuramotiFragment.newInstance(true)
    getSupportFragmentManager.beginTransaction.replace(R.id.activity_placeholder, fragment).commit
  }

  def importFudaset(dataString:String){
    try{
      val data = dataString.split("/").last
      val bytes = Base64.decode(data,Base64.URL_SAFE)
      val str = new String(bytes,"UTF-8")
      val obj = new JSONTokener(str).nextValue.asInstanceOf[JSONObject]
      val title = obj.keys.next.asInstanceOf[String]
      val ar = obj.getJSONArray(title)
      Utils.confirmDialog(this,Left(getResources.getString(R.string.confirm_action_view_fudaset,title,new java.lang.Integer(ar.length))),{ () =>
        var count = 0
        val res = (0 until ar.length).map{ i =>
          val o = ar.get(i).asInstanceOf[JSONObject]
          val name = o.keys.next.asInstanceOf[String]
          val n = BigInt(o.getString(name),36)
          val a = mutable.Buffer[Int]()
          for(j <- 0 until n.bitLength){
            if ( ((n >> j) & 1) == 1 ){
              a += j + 1
            }
          }
          val r = TrieUtils.makeKimarijiSetFromNumList(a.toList).exists{
            case (kimari,st_size) =>
              Utils.writeFudaSetToDB(name,kimari,st_size,true)
          }
          (if(r){count+=1;"[OK]"}else{"[NG]"}) + " " + name
        }
        val msg = getResources.getString(R.string.confirm_action_view_fudaset_done,new java.lang.Integer(count)) +
        "\n" + res.mkString("\n")
        Utils.messageDialog(this,Left(msg))
      })
    }catch{
      case e:Exception => {
        val msg = getResources.getString(R.string.confirm_action_view_fudaset_fail) + "\n" + e.toString
        Utils.messageDialog(this,Left(msg))
      }
    }
  }

  def restartRefreshTimer(){
    Globals.global_lock.synchronized{
      run_refresh_text.foreach(handler.removeCallbacks(_))
      run_refresh_text = None
      if(NotifyTimerUtils.notify_timers.nonEmpty){
        run_refresh_text = Some(new Runnable(){
          override def run(){
            if(NotifyTimerUtils.notify_timers.isEmpty){
              run_refresh_text.foreach(handler.removeCallbacks(_))
              run_refresh_text = None
              return
            }
            Utils.setButtonTextByState(getApplicationContext())
            run_refresh_text.foreach{handler.postDelayed(_,MINUTE_MILLISEC)}
          }
        })
        run_refresh_text.foreach{_.run()}
      }
    }
  }

  def refreshAndSetButton(force:Boolean = false, fromAuto:Boolean = false, nextRandom:Option[Int] = None){
    Globals.global_lock.synchronized{
      Globals.player = AudioHelper.refreshKarutaPlayer(this, Globals.player, force, fromAuto, nextRandom)
      Utils.setButtonTextByState(getApplicationContext(), fromAuto)
    }
  }

  def refreshAndInvalidate(fromAuto:Boolean = false){
    refreshAndSetButton(fromAuto = fromAuto)
    invalidateYomiInfo()
  }

  override def onCreateOptionsMenu(menu: Menu):Boolean = {
    getMenuInflater.inflate(R.menu.main, menu)
    super.onCreateOptionsMenu(menu)
  }

  def showShuffleDialog(){
    Utils.confirmDialog(this,Right(R.string.menu_shuffle_confirm), ()=>{
        FudaListHelper.shuffleAndMoveToFirst(getApplicationContext())
        refreshAndInvalidate()
    })
  }

  override def onOptionsItemSelected(item: MenuItem):Boolean = {
    KarutaPlayUtils.cancelAllPlay()
    Utils.setButtonTextByState(getApplicationContext())
    item.getItemId match {
      case R.id.menu_shuffle => {
        showShuffleDialog()
      }
      case R.id.menu_move => {
        val dlg = new MovePositionDialog()
        dlg.show(getSupportFragmentManager,"move_position")
      }
      case R.id.menu_timer => startActivity(new Intent(this,classOf[NotifyTimerActivity]))
      case R.id.menu_quick_conf => {
        val dlg = new QuickConfigDialog()
        dlg.show(getSupportFragmentManager,"quick_config")
      }
      case R.id.menu_conf => startActivity(new Intent(this,classOf[ConfActivity]))
      case android.R.id.home => {
        // android.R.id.home will be returned when the Icon is clicked if we are using android.support.v7.app.ActionBarActivity
        if(haseo_count < 3){
          haseo_count += 1
        }else{
          val layout = new RelativeLayout(this)
          val builder = new AlertDialog.Builder(this)
          val iv = new ImageView(this)
          iv.setImageResource(R.drawable.hasewo)
          iv.setAdjustViewBounds(true)
          iv.setScaleType(ImageView.ScaleType.FIT_XY)
          val metrics = getResources.getDisplayMetrics
          val maxw = metrics.widthPixels
          val maxh = metrics.heightPixels
          val width = iv.getDrawable.getIntrinsicWidth
          val height = iv.getDrawable.getIntrinsicHeight
          val ratio = width.toDouble/height.toDouble
          val OCCUPY_IN_SCREEN = 0.9
          val Array(tw,th) = (if(maxw/ratio < maxh){
            Array(maxw,maxw/ratio)
          }else{
            Array(maxh*ratio,maxh)
          })
          val Array(neww,newh) = (for (i <- Array(tw,th))yield (i*OCCUPY_IN_SCREEN).toInt)
          val params = new RelativeLayout.LayoutParams(neww,newh)
          params.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
          iv.setLayoutParams(params)
          layout.addView(iv)
          builder.setView(layout)
          val dialog = builder.create
          dialog.show
          // we have to get attributes after show()
          val dparams = dialog.getWindow.getAttributes
          dparams.height = newh
          dparams.width = neww
          dialog.getWindow.setAttributes(dparams)
          haseo_count = 0
        }
      }

      case _ => {}
    }
    return true
  }

  def setCustomActionBar(){
    val actionbar = getSupportActionBar
    val actionview = getLayoutInflater.inflate(R.layout.actionbar_custom,null)
    actionbar.setCustomView(actionview)
    actionbar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,ActionBar.DISPLAY_SHOW_CUSTOM)

    val bar_kima = actionview.findViewById(R.id.yomi_info_bar_kimari_container).asInstanceOf[ViewStub]
    if(bar_kima != null &&
      YomiInfoUtils.showPoemText &&
      Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true)
    ){
      bar_kima.inflate()
      actionbar.setDisplayShowTitleEnabled(false)
    }else{
      actionbar.setDisplayShowTitleEnabled(true)
    }
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    Utils.initGlobals(getApplicationContext)

    setContentView(R.layout.main_activity)
    
    // since onResume is always called after onCreate, we don't have to set have_to_resume_task = true
    val fragment = WasuramotiFragment.newInstance(false)

    getSupportFragmentManager.beginTransaction.replace(R.id.activity_placeholder, fragment).commit
    getSupportActionBar.setHomeButtonEnabled(true)
    if(Globals.IS_DEBUG){
      setTitle(getResources().getString(R.string.app_name) + " DEBUG")
      val layout = getWindow.getDecorView.findViewWithTag("main_linear_layout").asInstanceOf[LinearLayout]
      val view = new TextView(this)
      view.setTag("main_debug_info")
      view.setContentDescription("MainDebugInfo")
      layout.addView(view)
    }

  }
  def showProgress(){
    val v = getSupportActionBar.getCustomView
    if(v!=null){
      val ring = v.findViewById(R.id.actionbar_blue_ring)
      if(ring!=null){
        val rotation = AnimationUtils.loadAnimation(getApplicationContext,R.anim.rotator)
        rotation.setInterpolator(new Interpolator(){
            override def getInterpolation(input:Float):Float={
              return (input*8.0f).toInt/8.0f
            }
          })
        ring.setVisibility(View.VISIBLE)
        ring.startAnimation(rotation)
      }
    }
  }
  def hideProgress(){
    val v = getSupportActionBar.getCustomView
    if(v!=null){
      val ring = v.findViewById(R.id.actionbar_blue_ring)
      if(ring!=null){
        ring.clearAnimation()
        ring.setVisibility(View.INVISIBLE)
      }
    }
  }
  def invalidateYomiInfo(){
    if(!YomiInfoUtils.showPoemText){
      return
    }
    val yomi_info = findViewById(R.id.yomi_info).asInstanceOf[YomiInfoLayout]
    if(yomi_info != null){
      yomi_info.invalidateAndScroll()
    }
  }

  // There was user report that "Poem text differs with actually played audio".
  // Therefore we periodically check whether poem text and audio queue are same,
  // and set poem text if differs.
  def checkConsistencyBetweenPoemTextAndAudio(){
    Globals.player.foreach{ player =>
      val aq = player.audio_queue.collect{ case Left(w) => Some(w.num) }.flatten.distinct.toList
      if(aq.isEmpty){
        return
      }
      val yomi_info = findViewById(R.id.yomi_info).asInstanceOf[YomiInfoLayout]
      if(yomi_info == null){
        return
      }
      val sx = yomi_info.getScrollX
      val cur_view = Array(R.id.yomi_info_view_cur,R.id.yomi_info_view_next,R.id.yomi_info_view_prev).view
        .flatMap{ x => Option(yomi_info.findViewById(x).asInstanceOf[YomiInfoView]) }
        .find( _.getLeft == sx ) // only get current displayed YomiInfoView, which scroll ended
      if(cur_view.isEmpty){
        return
      }
      lazy val next_view =
        yomi_info.getNextViewId(cur_view.get.getId).flatMap{
          vid => Option(yomi_info.findViewById(vid).asInstanceOf[YomiInfoView])
        }
      val vq = List(cur_view.flatMap{_.rendered_num},
        if(aq.length > 1){
          next_view.flatMap{_.rendered_num}
        }else{
          None
        }
      ).flatten
      if(vq != aq){
        aq.zip(List(cur_view,next_view).flatten).foreach{ case (num,vw) =>
          vw.updateCurNum(Some(num))
          vw.invalidate()
        }
      }
      if(bar_poem_info_num.exists(_ != aq.head)){
        cur_view.foreach{ c =>
          c.updateCurNum(Some(aq.head))
          updatePoemInfo(c.getId)
        }
      }
    }
  }

  def updatePoemInfo(cur_view:Int){
    val yomi_cur = findViewById(cur_view).asInstanceOf[YomiInfoView]
    if(yomi_cur != null){
      val fudanum = yomi_cur.cur_num
      bar_poem_info_num = fudanum
      if(Globals.prefs.get.getBoolean("yomi_info_show_info_button",true)){
        for(
             main <- Option(getSupportFragmentManager.findFragmentById(R.id.activity_placeholder).asInstanceOf[WasuramotiFragment]);
             yomi_dlg <- Option(main.getChildFragmentManager.findFragmentById(R.id.yomi_info_search_fragment).asInstanceOf[YomiInfoSearchDialog])
         ){
               yomi_dlg.setFudanum(fudanum)
         }
      }
      val cv = getSupportActionBar.getCustomView
      if(cv != null && Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true)){
        val v_fn = cv.findViewById(R.id.yomi_info_search_poem_num).asInstanceOf[TextView]
        val v_kima = cv.findViewById(R.id.yomi_info_search_kimariji).asInstanceOf[TextView]
        if(v_fn != null && v_kima != null){
          val (fudanum_s,kimari) = YomiInfoSearchDialog.getFudaNumAndKimari(this,fudanum)
          v_fn.setText(fudanum_s)
          v_kima.setText(kimari)
        }
      }
    }
  }

  def getCurNumInView():Option[Int] = {
    Option(findViewById(R.id.yomi_info_view_cur).asInstanceOf[YomiInfoView]).flatMap{_.cur_num}
  }

  def scrollYomiInfo(id:Int,smooth:Boolean,do_after_done:Option[()=>Unit]=None){
    if(!YomiInfoUtils.showPoemText){
      return
    }
    val yomi_info = findViewById(R.id.yomi_info).asInstanceOf[YomiInfoLayout]
    if(yomi_info != null){
      yomi_info.scrollToView(id,smooth,false,do_after_done)
    }
  }

  override def onStart(){
    super.onStart()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
  }

  // code which have to be done:
  // (a) after reloadFragment() or in onResume() ... put it inside WasuramotiActivity.doWhenResume()
  // (b) after reloadFragment() or after onCreate() ... put it inside WasuramotiFragment.onViewCreated()
  // (c) only in onResume() ... put it inside WasuramotiActivity.onResume()
  def doWhenResume(){
    Globals.global_lock.synchronized{
      if(Globals.forceRefresh){
        KarutaPlayUtils.replay_audio_queue = None
      }
      if(Globals.player.isEmpty || Globals.forceRefresh){
        if(! Utils.readFirstFuda && FudaListHelper.getCurrentIndex(this) <=0 ){
          FudaListHelper.moveToFirst(this)
        }
        Globals.player = AudioHelper.refreshKarutaPlayer(this,Globals.player,false)
      }
    }
    Utils.setButtonTextByState(getApplicationContext)
    if(Globals.player.forall(!_.is_replay)){
      invalidateYomiInfo()
    }else{
      Globals.player.foreach{ p =>
        KarutaPlayUtils.replay_audio_queue.foreach{ q =>
          p.forceYomiInfoView(q)
        }
      }
    }
    this.setVolumeControlStream(Utils.getAudioStreamType)
    KarutaPlayUtils.setReplayButtonEnabled(this,
      if(Globals.is_playing){
        Some(false)
      }else{
        None
      }
    )
  }

  override def onResume(){
    super.onResume()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
    Utils.setStatusBarForLolipop(this)
    if(Globals.forceRestart){
      Globals.forceRestart = false
      reloadFragment()
    }else{
      doWhenResume()
    }
    Globals.global_lock.synchronized{
      Globals.player.foreach{ p =>
        // When screen is rotated, the activity is destroyed and new one is created.
        // Therefore, we have to reset the KarutaPlayer's activity
        p.activity = this
      }
    }
    handleActionView()
    restartRefreshTimer()
    startDimLockTimer()
    // TODO: remove `Globals.have_to_alert_ver_0_9_9` and `R.string.alert_ver_0_9_9` at 2017-02-23
    if(Globals.have_to_alert_ver_0_9_9){
      Utils.messageDialog(this,Right(R.string.alert_ver_0_9_9))
      Globals.have_to_alert_ver_0_9_9 = false
    }
    Globals.prefs.foreach{ p =>
      if(!p.contains("intended_use")){
        changeIntendedUse(true)
      }
    }
  }
  override def onPause(){
    super.onPause()
    release_lock.foreach(_())
    release_lock = None
    run_dimlock.foreach(handler.removeCallbacks(_))
    run_dimlock = None
    run_refresh_text.foreach(handler.removeCallbacks(_))
    run_refresh_text = None
    // Since android:configChanges="orientation" is not set to WasuramotiActivity,
    // we have to close the dialog at onPause() to avoid window leak.
    // without this, window leak occurs when rotating the device when dialog is shown.
    Utils.dismissAlertDialog()
  }
  override def onStop(){
    super.onStop()
    Utils.cleanProvidedFile(this,false)
  }

  // don't forget that this method may be called when device is rotated
  // also not that this is not called when app is terminated by user using task manager.
  // See:
  //   http://stackoverflow.com/questions/4449955/activity-ondestroy-never-called
  //   http://developer.android.com/reference/android/app/Activity.html#onDestroy%28%29
  override def onDestroy(){
    super.onDestroy()
    Utils.deleteCache(getApplicationContext,_=>true)
  }
  def startDimLockTimer(){
    Globals.global_lock.synchronized{
      release_lock.foreach(_())
      getWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      release_lock = {
          Some( () => {
            getWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
          })
      }
      rescheduleDimLockTimer()
    }
  }

  def rescheduleDimLockTimer(millisec:Option[Long]=None){
    val DEFAULT_DIMLOCK_MINUTES = 5
    Globals.global_lock.synchronized{
      run_dimlock.foreach(handler.removeCallbacks(_))
      run_dimlock = None
      var dimlock_millisec = millisec.getOrElse({
        MINUTE_MILLISEC * Utils.getPrefAs[Long]("dimlock_minutes", DEFAULT_DIMLOCK_MINUTES, 9999)
      })
      // if dimlock_millisec overflows then set default value
      if(dimlock_millisec < 0){
        dimlock_millisec = DEFAULT_DIMLOCK_MINUTES * MINUTE_MILLISEC
      }
      run_dimlock = Some(new Runnable(){
        override def run(){
          release_lock.foreach(_())
          release_lock = None
        }
      })
      run_dimlock.foreach{handler.postDelayed(_,dimlock_millisec)}
    }
  }

  def changeIntendedUse(first_config:Boolean = true){
    val builder = new AlertDialog.Builder(this)
    val view = getLayoutInflater.inflate(R.layout.intended_use_dialog,null)
    val radio_group = view.findViewById(R.id.intended_use_group).asInstanceOf[RadioGroup]
    Utils.setRadioTextClickListener(radio_group)
    (Globals.prefs.get.getString("intended_use","") match {
      case "study" => Some(R.id.intended_use_study)
      case "competitive" => Some(R.id.intended_use_competitive)
      case "recreation" => Some(R.id.intended_use_recreation)
      case _ => None
    }).foreach{ radio_group.check(_) }
    val that = this
    val listener = new DialogInterface.OnClickListener(){
      import FudaSetEditListDialog.{SortMode,ListItemMode}
      override def onClick(interface:DialogInterface,which:Int){
        val id = radio_group.getCheckedRadioButtonId
        if(id == -1){
          return
        }
        val edit = Globals.prefs.get.edit
        val changes = id match {
          case R.id.intended_use_competitive => {
            edit.putString("intended_use","competitive")
            edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.ABC,ListItemMode.KIMARIJI))
            edit.putString("read_order_each","CUR2_NEXT1")
            edit.putBoolean("joka_enable",true)
            edit.putBoolean("memorization_mode",false)
            edit.putBoolean("show_replay_last_button",false)
            YomiInfoUtils.hidePoemText(edit)
            Array(
              (R.string.intended_use_poem_text,R.string.quick_conf_hide),
              (R.string.intended_use_read_order,R.string.conf_read_order_name_cur2_next1),
              (R.string.intended_use_joka,R.string.intended_use_joka_on),
              (R.string.conf_memorization_title,R.string.message_disabled),
              (R.string.intended_use_replay,R.string.intended_use_replay_off)
            )
          }
          case R.id.intended_use_study => {
            edit.putString("intended_use","study")
            edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.NUM,ListItemMode.FULL))
            edit.putString("read_order_each","CUR1_CUR2")
            edit.putBoolean("joka_enable",false)
            edit.putBoolean("memorization_mode",true)
            edit.putBoolean("show_replay_last_button",false)
            YomiInfoUtils.showFull(edit)
             Array(
              (R.string.intended_use_poem_text,R.string.quick_conf_full),
              (R.string.intended_use_read_order,R.string.conf_read_order_name_cur1_cur2),
              (R.string.intended_use_joka,R.string.intended_use_joka_off),
              (R.string.conf_memorization_title,R.string.message_enabled),
              (R.string.intended_use_replay,R.string.intended_use_replay_off)
            )
          }
          case R.id.intended_use_recreation => {
            edit.putString("intended_use","recreation")
            edit.putString("fudaset_edit_list_dlg_mode",FudaSetEditListDialog.genDialogMode(SortMode.NUM,ListItemMode.FULL))
            edit.putString("read_order_each","CUR1_CUR2_CUR2")
            edit.putBoolean("joka_enable",false)
            edit.putBoolean("memorization_mode",false)
            edit.putBoolean("show_replay_last_button",true)
            YomiInfoUtils.showOnlyFirst(edit)
            Array(
              (R.string.intended_use_poem_text,R.string.quick_conf_only_first),
              (R.string.intended_use_read_order,R.string.conf_read_order_name_cur1_cur2_cur2),
              (R.string.intended_use_joka,R.string.intended_use_joka_off),
              (R.string.conf_memorization_title,R.string.message_disabled),
              (R.string.intended_use_replay,R.string.intended_use_replay_on)
            )
          }
          case _ => Array() // do nothing
        }
        val footnote = id match {
          case R.id.intended_use_competitive => Some(R.string.intended_use_competitive_footnote)
          case R.id.intended_use_study => Some(R.string.intended_use_study_footnote)
          case R.id.intended_use_recreation => Some(R.string.intended_use_recreation_footnote)
          case _ => None
        }
        edit.commit()
        FudaListHelper.updateSkipList(getApplicationContext)
        Globals.forceRefresh = true

        var html = "<big>" + getResources.getString(R.string.intended_use_result) + "</big><br>-------<br>" + changes.map({case(k,v)=>
          val kk = getResources.getString(k)
          val vv = getResources.getString(v)
          s"""&middot; ${kk} &hellip; <font color="#FFFF00">${vv}</font>"""
        }).mkString("<br>")  
        footnote.foreach{
          html += "<br>-------<br><big>" + getResources.getString(_) + "</big>"
        }
        val hcustom = (builder:AlertDialog.Builder) => {
          builder.setTitle(R.string.intended_use_result_title)
        }
        Utils.generalHtmlDialog(that,Left(html),()=>{
          reloadFragment()
        },custom = hcustom)
      }
    }
    builder.setView(view).setTitle(if(first_config){R.string.intended_use_title}else{R.string.quick_conf_intended_use})
    builder.setPositiveButton(android.R.string.yes,listener)
    if(first_config){
      builder.setCancelable(false)
    }else{
      builder.setNegativeButton(android.R.string.no,null)
    }
    val dialog = builder.create
    if(first_config){
      dialog.setOnShowListener(new DialogInterface.OnShowListener(){
        override def onShow(interface:DialogInterface){
          val button = dialog.getButton(DialogInterface.BUTTON_POSITIVE)
          val rgp = dialog.findViewById(R.id.intended_use_group).asInstanceOf[RadioGroup]
          if(rgp != null && button != null){
            rgp.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener(){
              override def onCheckedChanged(group:RadioGroup,checkedId:Int){
                button.setEnabled(true)
              }
            })
            button.setEnabled(false)
          }
        }
      })
    }
    Utils.showDialogAndSetGlobalRef(dialog)
  }
}

trait MainButtonTrait{
  self:WasuramotiActivity =>
  def onMainButtonClick(v:View) {
    doPlay(from_main_button=true)
  }
  def moveToNextFuda(showToast:Boolean = true, fromAuto:Boolean = false){
    val is_shuffle = ! Utils.isRandom
    if(is_shuffle){
      FudaListHelper.moveNext(self.getApplicationContext())
    }
    // In random mode, there is a possibility that same pairs of fuda are read in a row.
    // In that case, if we do not show "now loading" message, the user can know that same pairs are read.
    // Therefore we give force flag to true for refreshAndSetButton.
    self.refreshAndSetButton(!is_shuffle,fromAuto)
    if(Utils.readCurNext(self.getApplicationContext)){
      invalidateYomiInfo()
    }
    if(showToast && Globals.prefs.get.getBoolean("show_message_when_moved",true)){
      Toast.makeText(getApplicationContext,R.string.message_when_moved,Toast.LENGTH_SHORT).show()
    }
  }

  def doPlay(auto_play:Boolean = false, from_main_button:Boolean = false, from_swipe:Boolean = false){
    Globals.global_lock.synchronized{
      if(Globals.player.isEmpty){
        if(Globals.prefs.get.getBoolean("memorization_mode",false) &&
          FudaListHelper.getOrQueryNumbersToRead() == 0){
          Utils.messageDialog(self,Right(R.string.all_memorized))
        }else if(FudaListHelper.allReadDone(self.getApplicationContext())){
          val custom = (builder:AlertDialog.Builder) => {
            builder.setNeutralButton(R.string.menu_shuffle, new DialogInterface.OnClickListener(){
              override def onClick(dialog:DialogInterface, which:Int){
                showShuffleDialog()
              }
            })
          }
          Utils.messageDialog(self,Right(R.string.all_read_done),custom=custom)
        }else if(Globals.player_none_reason.nonEmpty){
          Utils.messageDialog(self,Left(Globals.player_none_reason.get))
        }else{
          Utils.messageDialog(self,Right(R.string.player_none_reason_unknown))
        }
        return
      }
      val player = Globals.player.get
      KarutaPlayUtils.cancelAutoTimer()
      if(Globals.is_playing){
        val have_to_go_next = (
          from_main_button &&
          Globals.prefs.get.getBoolean("move_after_first_phrase",true) &&
          ! player.is_replay &&
          player.isAfterFirstPhrase
        )
        player.stop()
        if(have_to_go_next){
          moveToNextFuda()
        }else{
          Utils.setButtonTextByState(self.getApplicationContext())
        }
      }else{
        // TODO: if auto_play then turn off display
        if(!auto_play){
          startDimLockTimer()
        }
        val bundle = new Bundle()
        bundle.putBoolean("have_to_run_border",YomiInfoUtils.showPoemText && Utils.readCurNext(self.getApplicationContext))
        bundle.putString("fromSender",KarutaPlayUtils.SENDER_MAIN)
        player.play(bundle,auto_play,from_swipe)
      }
    }
  }
}

trait ActivityDebugTrait{
  self:WasuramotiActivity =>
  def showBottomInfo(key:String,value:String){
    if(Globals.IS_DEBUG){
      val btn = getWindow.getDecorView.findViewWithTag("main_debug_info").asInstanceOf[TextView]
      val txt = (btn.getText.toString.split(";").map{_.split("=")}.collect{
        case Array(k,v)=>(k,v)
      }.toMap + ((key,value))).collect{case (k,v)=>k+"="+v}.mkString(";")
      btn.setText(txt)
    }
  }
  def showAudioLength(len:Long){
    if(Globals.IS_DEBUG){
      showBottomInfo("len",len.toString)
    }
  }
}

trait WasuramotiBaseTrait {
  self:Activity =>
  override def onOptionsItemSelected(item: MenuItem):Boolean = {
    item.getItemId match {
      case android.R.id.home => {
        self.finish()
      }
      case _ => {}
    }
    return true
  }
}
