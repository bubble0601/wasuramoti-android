package tami.pen.wasuramoti

object AllFuda{
  val list : Array[String] = Array(
"あきの","はるす","あし","たご","おく",
"かさ","あまの","わがい","はなの","これ",
"わたのはらや","あまつ","つく","みち","きみがためは",
"たち","ちは","す","なにわが","わび",
"いまこ","ふ","つき","この","なにし",
"おぐ","みかの","やまざ","こころあ","ありあ",
"あさぼらけあ","やまが","ひさ","たれ","ひとは",
"なつ","しら","わすら","あさじ","しの",
"こい","ちぎりき","あい","おおこ","あわれ",
"ゆら","やえ","かぜを","みかき","きみがためお",
"かく","あけ","なげき","わすれ","たき",
"あらざ","め","ありま","やす","おおえ",
"いに","よを","いまは","あさぼらけう","うら",
"もろ","はるの","こころに","あらし","さ",
"ゆう","おと","たか","うか","ちぎりお",
"わたのはらこ","せ","あわじ","あきか","ながか",
"ほ","おも","よのなかよ","ながら","よも",
"なげけ","む","なにわえ","たま","みせ",
"きり","わがそ","よのなかは","みよ","おおけ",
"はなさ","こぬ","かぜそ","ひとも","もも")
  val musumefusahose : String = "むすめふさほせうつしもゆいちひきはやよかみたこおわなあ"
  def compareMusumefusahose(x:String,y:String):Boolean = {
    val x1 = musumefusahose.indexOf(x(0))
    val y1 = musumefusahose.indexOf(y(0))
    if( x1 == y1 ){
      return x.compare(y) < 0
    }else{
      return x1.compare(y1) < 0
    }
  }
  def getFudaNum(s:String):Int = {
    val r = list.indexOf(s)
    if( r < 0){
      return -1
    }else{
      return r+1
    }
  }
}
