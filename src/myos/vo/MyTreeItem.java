package myos.vo;

import javafx.collections.ObservableList;
import javafx.scene.control.TreeItem;
import myos.manager.files.Catalog;

import java.io.IOException;

/**
 * 目录树视图
 *
 * @author WTDYang
 * @date 2022/12/10
 */
public class MyTreeItem extends TreeItem<Catalog>{
    private boolean notInitialized = true;
    public MyTreeItem(final Catalog catalog){
        super(catalog);
    }
    @Override
    public boolean isLeaf(){
        return !getValue().isDirectory();
    }
    @Override
    public ObservableList<TreeItem<Catalog>> getChildren(){
        if(notInitialized){
            notInitialized = false;
            if(getValue().isDirectory()&&!getValue().isBlank()){
                try {
                    for (Catalog c:getValue().list()){
                        super.getChildren().add(new MyTreeItem(c));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
        return super.getChildren();
    }
}
