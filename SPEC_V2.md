# Winamp 复刻 v2 — 主菜单功能规格

用户要求实现 Winamp 主窗口右键菜单的全部功能。安卓上以长按主窗口标题栏（或新增 MENU 按钮）弹出菜单。

## 菜单项 → 安卓实现

| 菜单 | 实现 |
|---|---|
| About Winamp... | 关于对话框（版本、说明） |
| Play ▶ | 子菜单：播放/暂停/停止/上一首/下一首（已有，接入菜单） |
| View file info... | 当前曲目信息对话框：标题/艺术家/专辑/时长/码率/采样率/声道（MediaMetadataRetriever + MediaExtractor 已有探测） |
| Bookmarks ▶ | 书签：为曲目记录播放位置（SharedPreferences），子菜单列出书签、跳转/删除 |
| Main Window / Playlist Editor / Equalizer | 三个窗口的显示开关（已有折叠，菜单里做勾选态同步） |
| Media Library | 暂缓（用户明确先不实现） |
| Video | 暂缓（用户明确先不实现） |
| Options ▶ | 设置子菜单：时间显示剩余/已播、退出确认、通知栏控制开关 |
| Playback ▶ | 随机/循环/单曲循环开关（已有，接入菜单） |
| Visualization ▶ | 频谱/示波器切换（已有点击切换，菜单里做选项态）+ 可视化灵敏度 |
| Skins ▶ | 换肤：内置 3 套配色（Classic 绿 / Amber 琥珀 / Blue 蓝），切换 Skin 常量重绘 |
| Winamp Help | 帮助对话框（操作说明） |
| Exit | 退出应用（按设置决定是否确认） |

## 备注
- v1 先保证在模拟器上安装/启动/播放验证通过，v2 再叠加本规格。
- Webamp 升级（皮肤布局数据 + 软 10 段 EQ）与本菜单规格可并入同一 v2 迭代，用户已确认"先等模拟器验证"。
