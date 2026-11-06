/* 卡背预览用的示例词。
 * 词条内容原样摘自 content/seed/cet6_cards.json（increase / adolescent / abandon）。
 * progress 是预览模拟值（真机来自 tb_progress）；abandon 故意没有进度，
 * 用来演示「无进度时 EF_STATS 自动跳过」。
 * 种子只有 6 个字段，没有 phrases / synonyms / relWords，所以那三块在预览里
 * 会被跳过 —— 这是手机的既有行为（无内容 = 不渲染）。
 */
window.CARDBACK_SAMPLES = [
  {
    "word": "increase",
    "phonetic": "/ɪnˈkriːs/",
    "meaning": "v. 增加；提高；增长\nn. 增长；增加量",
    "example": "The company plans to increase wages by five percent next year.\n公司打算明年把工资往上提五个点。",
    "approach": [
      "① 先看前缀 in-：它不是熟词「在里面」，而是拉丁语里表方向、表「加于其上」的标记，画面感就是东西被一层层往堆上添。",
      "② 再看 crease 这一截：它来自词根 cres / cret「生长、变大」，同族词 create 是「造出、使生长」，crescent 是「正在长胖的月牙」，所以这一截自带「越长越大」的意思。",
      "③ 把两截合起来：不断往里加、往堆上添，堆子就自己长高长胖，数量和长度一起往上走 ——> increase 增加、增长、提高。"
    ],
    "collocations": [
      "increase by 20% —— 往上蹿了两成",
      "an increase in price —— 价钱涨了一截",
      "a sharp increase —— 涨得相当猛",
      "increase to 100 —— 一路加到一百"
    ],
    "phrases": "",
    "synonyms": "",
    "relWords": "",
    "progress": {
      "easeFactor": 2.62,
      "intervalDays": 6,
      "reviewCount": 3
    }
  },
  {
    "word": "adolescent",
    "phonetic": "/ˌædəˈlesnt/",
    "meaning": "adj. 青春期的；青少年的 n. 青少年",
    "example": "Many adolescents struggle with rapid physical and emotional changes.\n不少青少年正卡在身体猛长、情绪也跟着乱的那几年。",
    "approach": [
      "① 先看词头 ad-：它表示「朝向、趋向」，是动作的起点，像 advance 里那股向前的力气，指明这件事有一个要去的方向——长大。",
      "② 再看中间 ol-：它来自拉丁语 alere（养育、成长），是 adult 与 aliment 共同的根，画的是「被养大」这条线，代表一个身体正在往上蹿的过程。",
      "③ 接着是 -esc-：这个后缀专门标记「开始变成、正在变成」，像 evanescent 的「渐渐」、convalescent 的「逐步康复」，它给整词安上了一个进度条。",
      "④ 最后 -ent 是现在分词后缀，把前面三块焊成一个「正在……的人」；ad-（朝着）+ ol（长大）+ -esc（正在变成）+ -ent（的人）——> 一个正朝着成年长过去、还没到站的人，即「青春期的；青少年」。"
    ],
    "collocations": [
      "adolescent development 青春期发育",
      "adolescent behavior 青少年行为",
      "during adolescence 在青春期"
    ],
    "phrases": "",
    "synonyms": "",
    "relWords": "",
    "progress": {
      "easeFactor": 2.35,
      "intervalDays": 1,
      "reviewCount": 1
    }
  },
  {
    "word": "abandon",
    "phonetic": "/əˈbændən/",
    "meaning": "v. 抛弃；遗弃；放弃",
    "example": "They had to abandon the car and walk the rest of the way.\n没办法，他们只好把车丢下，剩下的路全靠两条腿走完。",
    "approach": [
      "① 先看 abandon 这个词，它没有能拆开讲的前缀词根，也没有一个真正容易混的形近词，所以不硬凑词根和对比，直接从词义本身找画面：抛弃的核心，是把原本属于自己、跟着自己走的东西从身上卸下来，丢在一个地方，自己不再带走它。",
      "② 再把这个动作具体化：一个红头发、戴圆眼镜的少年，肩上一直扛着一只鼓鼓的麻袋，麻袋是他一路背着的东西；走到半路，他把肩上的麻袋整个卸下来，放在路边，自己直起身子往回走，麻袋孤零零留在原地，和他之间空出一段距离。",
      "③ 合起来看——『原本扛在自己肩上的东西被卸下、丢在原地、人转身离开、不再带走』这一整套动作，正是 abandon 的意思：抛弃、遗弃、放弃。 ——> 词义：抛弃、遗弃、放弃。"
    ],
    "collocations": [
      "abandon a plan —— 说扔就扔，计划不要了",
      "abandon ship —— 船要沉了，赶紧撤",
      "abandon oneself to —— 一头扎进去，沉溺在里头"
    ],
    "phrases": "",
    "synonyms": "",
    "relWords": "",
    "progress": null
  }
];
