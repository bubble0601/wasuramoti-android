package karuta.hpnpwd.wasuramoti

import _root_.android.app.Activity
import _root_.android.preference.PreferenceManager
import _root_.android.content.{Intent,Context}
import _root_.android.os.{Bundle,Handler,PowerManager}
import _root_.android.view.{View,Menu,MenuItem,ContextThemeWrapper}
import _root_.android.widget.Button
import _root_.java.lang.Runnable
import _root_.java.util.{Timer,TimerTask}


class WasuramotiActivity extends Activity with MainButtonTrait{
  var release_lock = None:Option[Unit=>Unit]
  def refreshKarutaPlayer(fromProperty:Boolean=true){
    Globals.player = (if(fromProperty){
      ReaderList.makeCurrentReader(getApplicationContext())
    }else{
      Globals.player.map{_.reader}
    }).flatMap(
      reader => AudioHelper.makeKarutaPlayer(getApplicationContext(),reader))
    if(Globals.player.isEmpty){
      Globals.setButtonText.foreach(func => func(Left(FudaListHelper.makeReadIndexMessage(getApplicationContext()))))
    }
  }
  override def onCreateOptionsMenu(menu: Menu) : Boolean = {
    super.onCreateOptionsMenu(menu)
    val inflater = getMenuInflater()
    inflater.inflate(R.menu.main, menu)
    return true
  }
  override def onOptionsItemSelected(item: MenuItem) : Boolean = {
    Globals.player.foreach(p=>{p.stop();p.setButtonTextByState()})
    item.getItemId match {
      case R.id.menu_shuffle => {
        Utils.confirmDialog(this,Right(R.string.menu_shuffle_confirm),_=>{
          FudaListHelper.shuffle(getApplicationContext())
          FudaListHelper.moveToFirst(getApplicationContext())
          Globals.player.foreach(_.setButtonTextByState)
          refreshKarutaPlayer()
        })
      }
      case R.id.menu_move => new MovePositionDialog(this,_=>{Globals.player.foreach(_.setButtonTextByState);refreshKarutaPlayer()}).show
      case R.id.menu_conf =>
        val intent = new Intent(this,classOf[ConfActivity])
        startActivity(intent)
    }
    return true
  }
  override def onResume(){
    super.onResume()
    Globals.player.foreach(p=>{p.stop();p.setButtonTextByState;})
    refreshKarutaPlayer()
    release_lock = if(Globals.prefs.get.getBoolean("enable_lock",false)){
      None
    }else{
      val power_manager = getSystemService(Context.POWER_SERVICE).asInstanceOf[PowerManager]
      val wake_lock = power_manager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"DoNotDimScreen")
      wake_lock.acquire()
      Some( _ => wake_lock.release ) 
    }
  }
  override def onPause(){
    super.onPause()
    release_lock.foreach(_())
  }

  override def onCreate(savedInstanceState: Bundle) {
    val context = this
    super.onCreate(savedInstanceState)
    val pinfo = getPackageManager().getPackageInfo(getPackageName(), 0)
    setTitle(getResources().getString(R.string.app_name) + " ver " + pinfo.versionName)
    Globals.database = Some(new DictionaryOpenHelper(getApplicationContext()))
    PreferenceManager.setDefaultValues(getApplicationContext(),R.xml.conf,false)
    Globals.prefs = Some(PreferenceManager.getDefaultSharedPreferences(getApplicationContext()))
    ReaderList.setDefaultReader(getApplicationContext())
    setContentView(R.layout.main)
    val read_button = findViewById(R.id.read_button).asInstanceOf[Button]
    val handler = new Handler()
    Globals.setButtonText = Some( arg =>
      handler.post(new Runnable(){
        override def run(){
          arg match {
            // TODO: The following way to call setText is not smart.
            //       Is there any way to do the follwing two lines in one row ?
            case Left(text) => read_button.setText(text)
            case Right(id) => read_button.setText(id)
          }
        }
      }))
  }
}

trait MainButtonTrait{
  self:WasuramotiActivity =>
  def onMainButtonClick(v:View) {
    Globals.global_lock.synchronized{
    val handler = new Handler()
    var timer_autoread = None:Option[Timer]
      if(Globals.player.isEmpty){
        Utils.messageDialog(self,Right(R.string.reader_not_found))
        return
      }
      val player = Globals.player.get
      timer_autoread.foreach(_.cancel())
      timer_autoread = None
      
      if(player.is_playing){
        if(!player.is_kaminoku){
          player.stop()
          player.setButtonTextByState
        }
      }else{
        player.play(
          _ => if("SHUFFLE" == Globals.prefs.get.getString("read_order",null)){ 
                 FudaListHelper.moveNext(self.getApplicationContext())
               },
          _ => {
            self.refreshKarutaPlayer(false)
            if(!Globals.player.isEmpty && Globals.prefs.get.getBoolean("read_auto",false)){
              timer_autoread = Some(new Timer())
              timer_autoread.get.schedule(new TimerTask(){
                override def run(){
                  handler.post(new Runnable(){
                    override def run(){onMainButtonClick(v)}
                  })
                  timer_autoread.foreach(_.cancel())
                  timer_autoread = None
                }},(Globals.prefs.get.getString("read_auto_span","0.0").toDouble*1000.0).toLong
              )
            }
        })
      }
    }
  }
}
