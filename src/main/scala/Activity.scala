package karuta.hpnpwd.wasuramoti

import _root_.android.app.{Activity,AlertDialog}
import _root_.android.media.AudioManager
import _root_.android.content.{Intent,Context,DialogInterface}
import _root_.android.util.{Base64,TypedValue,Log}
import _root_.android.os.{Bundle,Handler,Build}
import _root_.android.view.{View,Menu,MenuItem,WindowManager,ViewStub}
import _root_.android.view.animation.{AnimationUtils,Interpolator}
import _root_.android.widget.{ImageView,Button,RelativeLayout,TextView,LinearLayout,RadioGroup,Toast}
import _root_.android.support.v7.app.{ActionBarActivity,ActionBar}
import _root_.org.json.{JSONTokener,JSONObject,JSONArray}
import _root_.java.lang.Runnable
import _root_.karuta.hpnpwd.audio.OggVorbisDecoder
import scala.collection.mutable


class WasuramotiActivity extends ActionBarActivity with MainButtonTrait with ActivityDebugTrait{
  val MINUTE_MILLISEC = 60000
  var haseo_count = 0
  var release_lock = None:Option[()=>Unit]
  var run_dimlock = None:Option[Runnable]
  var run_refresh_text = None:Option[Runnable]
  val handler = new Handler()

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
    // we dont need the current intent anymore so replace it with default intent
    // Note: this intent will be used in Utils.restartActivity()
    setIntent(new Intent(this,this.getClass))
    dataString.replaceFirst("wasuramoti://","").split("/")(0) match {
      case "fudaset" => importFudaset(dataString)
      case "from_oom" => Utils.messageDialog(this,Right(R.string.from_oom_message))
      case m => Utils.messageDialog(this,Left(s"'${m}' is not correct intent data for ACTION_VIEW for wasuramoti"))
    }
  }


  def importFudaset(dataString:String){
    if(android.os.Build.VERSION.SDK_INT < 8){
      // Base64 was added in API >= 8
      Utils.messageDialog(this,Left("Sorry. Importing group of poem sets is supported in Android >= 2.2"))
      return
    }
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
      if(!NotifyTimerUtils.notify_timers.isEmpty){
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

  def cancelAllPlay(){
    Globals.player.foreach(_.stop())
    KarutaPlayUtils.cancelKarutaPlayTimer(
      getApplicationContext,
      KarutaPlayUtils.Action.Auto
    )
  }

  def refreshAndSetButton(force:Boolean = false){
    Globals.global_lock.synchronized{
      Globals.player = AudioHelper.refreshKarutaPlayer(this,Globals.player,force)
      Utils.setButtonTextByState(getApplicationContext())
    }
  }

  def refreshAndInvalidate(){
    refreshAndSetButton()
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
    cancelAllPlay()
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
        // android.R.id.home is returned when the Icon is clicked if we are using android.support.v7.app.ActionBarActivity
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

  def switchViewAndReloadHandler(){
    val read_button = findViewById(R.id.read_button).asInstanceOf[Button]
    val stub = findViewById(R.id.yomi_info_stub).asInstanceOf[ViewStub]
    if(YomiInfoUtils.showPoemText){
      stub.inflate()
      read_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.read_button_text_normal))
      read_button.setBackgroundResource(R.drawable.main_button)
    }else{
      // Android 2.1 does not ignore ViewStub's layout_weight
      val lp = stub.getLayoutParams.asInstanceOf[LinearLayout.LayoutParams]
      lp.weight = 0.0f
      stub.setLayoutParams(lp)
    }

    val frag_stub = findViewById(R.id.yomi_info_search_stub).asInstanceOf[ViewStub]
    if(frag_stub != null && 
      YomiInfoUtils.showPoemText &&
      Globals.prefs.get.getBoolean("yomi_info_show_info_button",true) &&
      Utils.isScreenWide(this)
    ){
      frag_stub.inflate()
      val fragment = YomiInfoSearchDialog.newInstance(false,0)
      getSupportFragmentManager.beginTransaction.replace(R.id.yomi_info_search_fragment,fragment).commit
    }else if(frag_stub != null){
      // Android 2.1 does not ignore ViewStub's layout_weight
      val lp = frag_stub.getLayoutParams.asInstanceOf[LinearLayout.LayoutParams]
      lp.weight = 0.0f
      frag_stub.setLayoutParams(lp)
    }

    Globals.setButtonText = Some( txt =>
      handler.post(new Runnable(){
        override def run(){
          val lines = txt.split("\n").length
          if(lines >= 4 && YomiInfoUtils.showPoemText){
            read_button.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources.getDimension(R.dimen.read_button_text_small))
          }
          read_button.setLines(lines)
          read_button.setText(txt)
        }
      }))
  }

  def setCustomActionBar(){
    val actionbar = getSupportActionBar
    val actionview = getLayoutInflater.inflate(R.layout.actionbar_custom,null)
    actionbar.setCustomView(actionview)
    actionbar.setDisplayOptions(ActionBar.DISPLAY_SHOW_CUSTOM,ActionBar.DISPLAY_SHOW_CUSTOM)

    val bar_kima = actionview.findViewById(R.id.yomi_info_bar_kimari_container).asInstanceOf[ViewStub]
    if(bar_kima != null &&
      YomiInfoUtils.showPoemText &&
      Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true) &&
      Utils.isScreenWide(this)
    ){
      bar_kima.inflate()
      actionbar.setDisplayShowTitleEnabled(false)
    }
  }

  override def onCreate(savedInstanceState: Bundle) {
    super.onCreate(savedInstanceState)
    Utils.initGlobals(getApplicationContext())

    //try loading 'libstbvorbis.so'
    new OggVorbisDecoder()
    if(!OggVorbisDecoder.library_loaded){
      Utils.messageDialog(this,Right(R.string.cannot_load_vorbis_library), {() => finish()})
      return
    }
    if(YomiInfoUtils.showPoemText && android.os.Build.VERSION.SDK_INT >= 11){
         getWindow.setFlags(
           WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
           WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
    }
    setContentView(R.layout.main)
    getSupportActionBar.setHomeButtonEnabled(true)
    switchViewAndReloadHandler()
    setCustomActionBar()
    this.setVolumeControlStream(AudioManager.STREAM_MUSIC)
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

  def updatePoemInfo(cur_view:Int){
    val yomi_cur = findViewById(cur_view).asInstanceOf[YomiInfoView]
    if(yomi_cur != null){
      yomi_cur.cur_num.foreach{fudanum =>
        val yomi_dlg = getSupportFragmentManager.findFragmentById(R.id.yomi_info_search_fragment).asInstanceOf[YomiInfoSearchDialog]
        if(yomi_dlg != null && Globals.prefs.get.getBoolean("yomi_info_show_info_button",true)){
          yomi_dlg.setFudanum(fudanum)
        }
        val cv = getSupportActionBar.getCustomView
        if(cv!=null && Globals.prefs.get.getBoolean("yomi_info_show_bar_kimari",true)){
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

  def checkConsintencyForYomiInfoAndAudioQueue(audio_queue:Utils.AudioQueue):Boolean = {
    // TODO: can we safely assume that .distinct() returns the list in original order ?
    val read_nums = audio_queue.collect{ case Left(w) => Some(w.num) }.distinct.toList
    val yomi_info = findViewById(R.id.yomi_info).asInstanceOf[YomiInfoLayout]
    if(yomi_info != null){
      val yomi_nums = yomi_info.should_be_played_list
      val r = (yomi_nums == read_nums)
      if(!r){
        Log.v("wasuramoti",s"text audio inconsistent: text=${yomi_nums}, audio=${read_nums}")
        Log.v("wasuramoti",s"karuta_player=${Globals.player.map(_.toBugReport)}")
      }
      r
    }else{
      true
    }
  }

  override def onStart(){
    super.onStart()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
  }
  override def onResume(){
    super.onResume()
    if( Globals.prefs.isEmpty ){
      // onCreate returned before loading preference
      return
    }
    if(Globals.forceRestart){
      Globals.forceRestart = false
      Utils.restartActivity(this)
    }
    restartRefreshTimer()
    Globals.global_lock.synchronized{
      Globals.player.foreach{ p =>
        // When screen is rotated, the activity is destroyed and new one is created.
        // Therefore, we have to reset the KarutaPlayer's activity
        p.activity = this
      }
      if(Globals.player.isEmpty || Globals.forceRefresh){
        if(! Utils.readFirstFuda && FudaListHelper.getCurrentIndex(this) <=0 ){
          FudaListHelper.moveToFirst(this)
        }
        Globals.player = AudioHelper.refreshKarutaPlayer(this,Globals.player,false)
      }
      Utils.setButtonTextByState(getApplicationContext)
    }
    invalidateYomiInfo()
    startDimLockTimer()
    setLongClickButtonOnResume()
    setLongClickYomiInfoOnResume()
    handleActionView()
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
    Utils.dismissAlertDialog()
  }
  override def onStop(){
    super.onStop()
  }
  override def onDestroy(){
    Utils.deleteCache(getApplicationContext(),_=>true)
    super.onDestroy()
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

  def setLongClickYomiInfoOnResume(){
    for(id <- Array(R.id.yomi_info_view_prev,R.id.yomi_info_view_cur,R.id.yomi_info_view_next)){
      val view = findViewById(id).asInstanceOf[YomiInfoView]
      if(view != null){
        view.setOnLongClickListener(
          new View.OnLongClickListener(){
            override def onLongClick(v:View):Boolean = {
              view.cur_num.foreach{num=>
                val dlg = YomiInfoSearchDialog.newInstance(true,view.cur_num.getOrElse(num))
                dlg.show(getSupportFragmentManager,"yomi_info_search")
              }
              return true
            }
          }
        )
      }
    }
  }
  def setLongClickButtonOnResume(){
    val btn = findViewById(R.id.read_button).asInstanceOf[Button]
    if(btn != null){
      btn.setOnLongClickListener(
        if(Globals.prefs.get.getBoolean("skip_on_longclick",false)){
          new View.OnLongClickListener(){
            override def onLongClick(v:View):Boolean = {
              Globals.global_lock.synchronized{
                if(Globals.is_playing){
                  Globals.player.foreach{p=>
                    p.stop()
                    moveToNextFuda()
                    doPlay()
                  }
                }
              }
              return true
            }
          }
        }else{
          null
        }
      )
    }
  }
  def changeIntendedUse(first_config:Boolean = true){
    val view = getLayoutInflater.inflate(R.layout.intended_use_dialog,null)
    val radio_group = view.findViewById(R.id.intended_use_group).asInstanceOf[RadioGroup]
    val check_id = Globals.prefs.get.getString("intended_use","competitive") match {
      case "study" => R.id.intended_use_study
      case "recreation" => R.id.intended_use_recreation
      case _ => R.id.intended_use_competitive
    }
    radio_group.check(check_id)

    val on_yes = () => {
      val edit = Globals.prefs.get.edit
      val id = radio_group.getCheckedRadioButtonId
      val changes = id match {
        case R.id.intended_use_competitive => {
          edit.putString("intended_use","competitive")
          edit.putString("read_order_each","CUR2_NEXT1")
          edit.putString("read_order_joka","upper_1,lower_1")
          YomiInfoUtils.hidePoemText(edit)
          Array(
            (R.string.intended_use_poem_text,R.string.quick_conf_hide),
            (R.string.intended_use_read_order,R.string.conf_read_order_each_cur2_next1),
            (R.string.intended_use_joka,R.string.intended_use_joka_on)
          )
        }
        case R.id.intended_use_study => {
          edit.putString("intended_use","study")
          edit.putString("read_order_each","CUR1_CUR2")
          edit.putString("read_order_joka","upper_0,lower_0")
          YomiInfoUtils.showFull(edit)
           Array(
            (R.string.intended_use_poem_text,R.string.quick_conf_full),
            (R.string.intended_use_read_order,R.string.conf_read_order_each_cur1_cur2),
            (R.string.intended_use_joka,R.string.intended_use_joka_off)
          )
        }
        case R.id.intended_use_recreation => {
          edit.putString("intended_use","recreation")
          edit.putString("read_order_each","CUR1_CUR2_CUR2")
          edit.putString("read_order_joka","upper_0,lower_0")
          YomiInfoUtils.showOnlyFirst(edit)
          Array(
            (R.string.intended_use_poem_text,R.string.quick_conf_only_first),
            (R.string.intended_use_read_order,R.string.conf_read_order_each_cur1_cur2_cur2),
            (R.string.intended_use_joka,R.string.intended_use_joka_off)
          )
        }
        case _ => return
      }
      edit.commit()
      Globals.forceRefresh = true

      val html = "<big>" + getResources.getString(R.string.intended_use_result) + "<br>-------<br>" + changes.map({case(k,v)=>
        val kk = getResources.getString(k)
        val vv = getResources.getString(v)
        s"""&middot; ${kk} &hellip; <font color="#FFFF00">${vv}</font>"""
      }).mkString("<br>") + "</big>"

      Utils.generalHtmlDialog(this,Left(html),()=>{
        Utils.restartActivity(this)
      })
      ()
    }
    val custom = (builder:AlertDialog.Builder) => {
      builder.setView(view).setTitle(if(first_config){R.string.intended_use_title}else{R.string.quick_conf_intended_use})
    }
    Utils.confirmDialog(this,Right(R.string.intended_use_desc),on_yes,custom = custom)
  }
}

trait MainButtonTrait{
  self:WasuramotiActivity =>
  def onMainButtonClick(v:View) {
    doPlay(from_main_button=true)
  }
  def moveToNextFuda(){
    val is_shuffle = ! Utils.isRandom
    if(is_shuffle){
      FudaListHelper.moveNext(self.getApplicationContext())
    }
    // In random mode, there is a possibility that same pairs of fuda are read in a row.
    // In that case, if we do not show "now loading" message, the user can know that same pairs are read.
    // Therefore we give force flag to true for refreshAndSetButton.
    self.refreshAndSetButton(!is_shuffle)
    if(Utils.readCurNext(self.getApplicationContext)){
      invalidateYomiInfo()
    }
  }
  def doPlay(auto_play:Boolean = false, from_main_button:Boolean = false, from_swipe:Boolean = false){
    Globals.global_lock.synchronized{
      if(Globals.player.isEmpty){
        if(FudaListHelper.allReadDone(self.getApplicationContext())){
          val custom = (builder:AlertDialog.Builder) => {
            builder.setNeutralButton(R.string.menu_shuffle, new DialogInterface.OnClickListener(){
              override def onClick(dialog:DialogInterface, which:Int){
                showShuffleDialog()
              }
            })
          }
          Utils.messageDialog(self,Right(R.string.all_read_done),custom=custom)
        }else{
          Utils.messageDialog(self,Right(R.string.reader_not_found))
        }
        return
      }
      val player = Globals.player.get
      KarutaPlayUtils.cancelKarutaPlayTimer(
        getApplicationContext,
        KarutaPlayUtils.Action.Auto
      )
      if(Globals.is_playing){
        val have_to_go_next = (
          from_main_button &&
          Globals.prefs.get.getBoolean("move_after_first_phrase",true) &&
          player.isAfterFirstPoem)
        player.stop()
        if(have_to_go_next){
          moveToNextFuda()
          Toast.makeText(getApplicationContext,R.string.move_after_first_phrase_done,Toast.LENGTH_SHORT).show()
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
        // Since we insert some silence at beginning of audio,
        // the actual wait_time should be shorter.
        val (_,wait_time) = Utils.calcSilenceAndWaitLength
        bundle.putLong("wait_time",wait_time.toLong)
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
