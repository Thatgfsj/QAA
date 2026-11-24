# Termux extra-keys 调研（#93 参考）

调研对象：termux-app master（GPLv3）`termux-shared/…/termux/extrakeys/`。QAA #93 的快捷键增强以其「主键+次选」结构为参考。

## 底栏组成（四层）

1. **配置**：`~/.termux/termux.properties` 的 `extra-keys` JSON，`termux-reload-settings` 生效。默认两行 7 列：
   `[['ESC','/',{key:'-',popup:'|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]`
   默认行里 `-` 键自带次选 `|`——「主键+次选」是默认词表的一部分；`extra-keys-style` 控制按键字形。
2. **解析**：`ExtraKeysInfo` 把 JSON 解析成 `ExtraKeyButton[][]` 矩阵；语法支持 `KEY`、`{key, popup}`、`{macro:'CTRL d', display:'…'}`（macro 为空格分隔的按键序列）。
3. **视图**：`ExtraKeysView extends GridLayout`，`reload()` 按矩阵动态创建 MaterialButton 等宽格；按下变色；触摸状态机管理弹层与长按。
4. **消费**：`TerminalExtraKeys` 注入终端（`PRIMARY_KEY_CODES_FOR_STRINGS` 映射 KEYCODE；macro 拆分并携带修饰）。CTRL/ALT/SHIFT/FN 为 sticky 特殊键：短按激活一次作用于下一键，长按锁定。KEYBOARD(⌨)/DRAWER(☰) 为字符串约定，由 App 层 `TermuxTerminalViewClient.onToggleSoftKeyboardRequest()` 拦截处理，不注入终端；KEYBOARD 不在默认键行中。

## 触发手势（与 QAA 的差异）

- Termux 次选为「从按键上滑」呼出：按住并滑出键面弹 `PopupWindow`，滑回取消，松手触发。
- 长按另有语义：`UP/DOWN/LEFT/RIGHT/BKSP/DEL/PGUP/PGDN` 长按（默认约 400ms）后按重复间隔自动连发；修饰键长按锁定。
- QAA #93 取「长按呼出次选、点击执行」：可发现性更好，键位功能不受影响。
