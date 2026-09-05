package com.example.bbsanimatedbreak.client;

/**
 * 文件夹视图 Duck Typing 接口
 *
 * Mixin 类（UIReplayListMixin）注入到 UIReplayList 后，UIReplaysListPanelMixin
 * 无法直接把 UIReplayList 强转为 UIReplayListMixin（Mixin 类不是类型层次的一部分）。
 *
 * 通过此接口，UIReplayListMixin 实现这些方法，UIReplaysListPanelMixin 通过
 * ((IFolderView) this.replays).bbs$xxx() 调用。
 *
 * Mixin 会在目标类中生成这些方法实现，所以运行时强转有效。
 *
 * 注意：此接口必须放在非 mixin 包中。如果放在 mixin 包（被 *.mixins.json 的
 * "package" 字段声明）里，Mixin 系统会拒绝外部类直接引用它，导致
 * IllegalClassLoadError。
 */
public interface IFolderView
{
    /** 退出文件夹视图，回到根视图 */
    void bbs$exitFolder();

    /** 获取当前进入的文件夹名（null = 根视图） */
    String bbs$getEnteredFolder();

    /** 打开"移出文件夹"二级菜单 */
    void bbs$openMoveOutContextMenu();
}
