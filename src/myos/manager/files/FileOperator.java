package myos.manager.files;

import myos.Software;
import myos.constant.OsConstant;
import myos.controller.MainController;
import myos.manager.process.CPU;
import myos.manager.process.ProcessCreator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;


/**
 * 文件操作
 *
 * @author WTDYang
 * @date 2022/12/09
 */
@SuppressWarnings("all")
public class FileOperator {

    /**
     * 打开文件
     */
    List<OpenedFile> openedFiles;
    /**
     * 磁盘文件
     */
    public DiskDriver disk;
    /**
     * 进程操作
     */
    ProcessCreator processCreator;
    /**
     * 主控制器界面
     */
    private MainController mainController;

    public FileOperator(){
        init();
    }

    /**
     * 初始化
     */
    public void init() {
        this.disk = new DiskDriver( Software.disk);
        this.processCreator = Software.processCreator;
        this.openedFiles = new ArrayList<>();
    }

    /**
     * 建立文件
     *
     * @param filePath 文件路径名
     * @param property 文件属性
     */
    public void create(String filePath, int property) throws Exception {

        //寻找第一个空闲块
        int newFilePos = disk.firstFreeBlock();
        if (newFilePos == -1) {
            throw new Exception("硬盘空间不足");
        }
        //目录分割
        SplitFilePath splitFilePath = SplitFilePath.splitPathAndFileName(filePath);
        //找到该文件父目录所在磁盘块
        int parentCatalogBlockPos = disk.getCatalogBlock(splitFilePath.getPath(), 2);
        //读取父级目录的FCB
        Catalog parentDir = disk.readCatalog(parentCatalogBlockPos);
        //查找该文件夹下是否有同名目录
        if (disk.existsFile(splitFilePath.getFileName(), parentDir.getStartBlock())) {
            throw new Exception("已经存在同名目录，请先删除");
        }
        //将该文件夹的起始磁盘块设置为新文件的磁盘块
        if (parentDir.getStartBlock() == -1) {
            parentDir.setStartBlock(newFilePos);
            writeCatalog(parentDir);
        }else {
            //将该文件夹的最后一个文件的磁盘块设置为新文件的磁盘块位置
            int last = disk.getLastBlock(parentDir.getStartBlock());
            disk.seek(last);
            disk.writeByte(newFilePos);
        }
        Catalog newFile = new Catalog(splitFilePath.getFileName(), property);
        newFile.setCatalogBlock(newFilePos);
        //修改文件分配表
        setNextBlock(newFilePos,-1);
        //将目录项写入磁盘
        writeCatalog(newFile);
        System.out.println("建立文件成功");
        mainController.addTreeItem(parentDir,newFile);
    }

    /**
     * 建立目录
     *
     * @param dirPath 目录路径
     */
    public void mkdir(String dirPath) throws Exception {
        create(dirPath, 8);
    }

    /**
     * 打开文件
     *
     * @param filePath 文件名
     * @param opType   操作类型(读或写)
     */
    public OpenedFile open(String filePath, int opType) throws Exception {
        OpenedFile openedFile;
        int catalogBlockPos = -1;
        //查找文件目录项所在磁盘块
        try {

            catalogBlockPos = disk.getCatalogBlock(filePath, 2);
        } catch (Exception e) {
            throw new Exception("没有找到目标文件！");
        }
        //读取FCB
        Catalog catalog = disk.readCatalog(catalogBlockPos);
       // catalog.setCatalogBlock(catalogBlockPos);
        //创建打开文件
        openedFile = new OpenedFile();
        //打开方式
        openedFile.setOpType(opType);
        //打开文件路径
        openedFile.setFilePath(filePath);
        //打开文件FCB
        openedFile.setCatalog(catalog);
        //读取文件指针
        Pointer readPointer = new Pointer();
        //一开始指向文件（块号和块内地址）
        readPointer.setBlockNo(catalog.getStartBlock());
        readPointer.setAddress(0);
        //写文件指针同样如此初始化
        Pointer writePointer = new Pointer();
        writePointer.setBlockNo(catalog.getStartBlock());
        writePointer.setAddress(0);
        //指针写入打开文件
        openedFile.setReadPointer(readPointer);
        openedFile.setWritePointer(writePointer);
        //加入打开文件列表
        openedFiles.add(openedFile);
        return openedFile;
    }

    /**
     * 运行文件
     *
     * @param filePath
     */
    public void run(String filePath) throws Exception {
        OpenedFile openedFile = open(filePath, OpenedFile.OP_TYPE_RUN);
        //代码段
        byte[] instructions = read(openedFile, -1);
        //数据段
        byte[] data = new byte[0];
        processCreator.create(instructions,data);
    }

    /**
     * 读取文件
     *
     * @param filePath 文件路径名
     * @param length   要读取的字节数,-1表示读取所有
     */
    public String read(String filePath, int length) throws Exception {
        OpenedFile file = null;
        String s = null;
        try {
            file = open(filePath, OpenedFile.OP_TYPE_READ);
            byte[] bytes = read(file, length);
            s = new String(bytes);
        }finally {
            close(file);
        }
        return s;
    }

    /**
     * 写文件
     *
     * @param filePath 文件路径名
     * @param buffer   要写入的缓冲区数据
     * @param length   数据的长度
     */
    public void write(String filePath, String data,int type) throws Exception {
        OpenedFile openedFile = open(filePath,OpenedFile.OP_TYPE_WRITE);
        try {
            byte[] buffer = data.getBytes();
            if (type == 1) {
                write(openedFile, buffer, buffer.length);
            }else {
                append(openedFile,buffer,buffer.length);
            }
        }finally {
            close(openedFile);
        }

    }

    /**
     * 以追加的方式写入
     *
     * @param filePath
     * @param buffer
     * @param length
     */
    private void append(OpenedFile openedFile, byte[] buffer, int length) throws Exception {
        //追加写入
        Pointer p = openedFile.getWritePointer();
        //空文件
        if (openedFile.getCatalog().getStartBlock() == -1) {
            write(openedFile,buffer,length);
            return;
        }
        p.setBlockNo(disk.getLastBlock(openedFile.getCatalog().getStartBlock()));
        disk.seek(p.getBlockNo() * OsConstant.DISK_BLOCK_SIZE);
        byte b;
        int i = 0;
        //寻找最后一位
        while ((b = disk.readByte()) != '#') {
            i++;
        }
        p.setAddress(i);
        write(openedFile, buffer, length);
    }

    /**
     * 复制文件
     *
     * @param srcFilePath 资源文件路径
     * @param desFilePath 目标文件路径
     * @throws Exception 异常
     */
    public void copy(String srcFilePath,String desFilePath) throws Exception {
        OpenedFile openedFile1=null,openedFile2=null;
        try {
            openedFile1 = open(srcFilePath, OpenedFile.OP_TYPE_READ);
            create(desFilePath, openedFile1.getCatalog().getProperty());
            openedFile2 = open(desFilePath, OpenedFile.OP_TYPE_WRITE);
            byte[] content = read(openedFile1, -1);
            write(openedFile2, content, content.length);
        }catch (Exception e){
            throw e;
        }finally {
            if (openedFile1!=null) {
                close(openedFile1);
            }
            if (openedFile2!=null){
                close(openedFile2);
            }
        }

    }

    /**
     * 关闭文件
     *
     */
    public void close(OpenedFile openedFile) throws Exception {
        if (openedFile != null) {
            openedFiles.remove(openedFile);
        }
    }

    /**
     * 删除文件
     *
     * @param filePath 文件名
     */
    public void delete(String filePath) throws Exception {
        OpenedFile openedFile = null;
        try {
            openedFile = getOpenedFile(filePath);
        } catch (Exception e) {
            System.out.println("文件未打开，可以删除");
        }
        if (openedFile != null) {
            throw new Exception("该文件已经被打开，不能删除");
        }
        //找到文件目录所在磁盘块
        int blockPos = disk.getCatalogBlock(filePath, 2);
        System.out.println("文件目录项所在磁盘块：" + blockPos);
        Catalog catalog = disk.readCatalog(blockPos);
        int nextBlock = catalog.getStartBlock();
        int pre;
        //清空文件内容(分配表)
        while (nextBlock != -1) {
            pre = nextBlock;
            nextBlock = disk.getNextBlock(pre);
            setNextBlock(pre, 0);

        }
        //修改目录指针
        //如果是父文件夹的第一个目录，则修改父文件夹的开始盘块，
        //否则将上一个目录的指针指向下一个目录
        SplitFilePath splitFilePath = SplitFilePath.splitPathAndFileName(filePath);
        int parentPos = disk.getCatalogBlock(splitFilePath.getPath(), 2);
        Catalog parentDir = disk.readCatalog(parentPos);
        if (parentDir.getStartBlock() == blockPos) {
            parentDir.setStartBlock(disk.getNextBlock(blockPos));
            writeCatalog(parentDir);
        } else {
            nextBlock = parentDir.getStartBlock();
            pre = nextBlock;
            while (nextBlock != blockPos) {
                pre = nextBlock;
                nextBlock = disk.getNextBlock(pre);
            }
            setNextBlock(pre, disk.getNextBlock(blockPos));
        }
        //删除目录项
        setNextBlock(blockPos, 0);

        System.out.println("删除文件成功");
        mainController.removeTreeItem(catalog);
    }

    /**
     * 删除文件
     *
     * @param parent 父
     * @param c      c
     * @throws Exception 异常
     */
    public void delete(Catalog parent,Catalog c) throws Exception {
        //判断是否处于打开状态
        if(!isOpen(c)){throw  new Exception("该文件已经打开，无法删除！");}
        //物理删除文件
        disk.delete(parent,c);
    }

    /**
     * 显示文件
     *
     * @param filePath 文件名
     */
    public String type(String filePath) throws Exception {
        OpenedFile openedFile=open(filePath,OpenedFile.OP_TYPE_READ);
        byte[] content = read(openedFile, -1);
        close(openedFile);
        return new String(content);
    }

    /**
     * 改变文件属性
     *
     * @param filePath
     * @param newProperty
     */
    public void changeProperty(String filePath, int newProperty) throws Exception {
        int catalogBlock = disk.getCatalogBlock(filePath, 2);
        Catalog catalog = disk.readCatalog(catalogBlock);
        catalog.setProperty(newProperty);
        writeCatalog(catalog);
        System.out.println("修改文件属性成功");
        mainController.updateTreeItem(catalog);
    }

    /**
     * 显示目录的内容
     *
     * @param dirPath
     */
    public List<String> dir(String dirPath) throws Exception {
        //读取到文件列表
        List<Catalog> catalogs = disk.dir(dirPath);
        //收集位字符串数组传给视图层显示
        List<String> names = catalogs.stream().map(e -> {
            String s = String.format("%10s  -  type:%5s\n", e.getName(),
                    e.isDirectory()?"dir":(e.isExecutable()?"exe":"file"));
            return s;
        }).collect(Collectors.toList());
        return names;
    }

    /**
     * 删除文件夹
     *
     * @param dirPath 目录路径
     * @throws Exception 异常
     */
    public void rmdir(String dirPath) throws Exception {
        //获取文件路径
        SplitFilePath splitFilePath = SplitFilePath.splitPathAndFileName(dirPath);
        //获取父级目录块号
        int parentBlock = disk.getCatalogBlock(splitFilePath.getPath(),2);
        //读取父级目录FCB
        Catalog parent = disk.readCatalog(parentBlock);
        //要删除的文件的块号
        int catalogBlock = disk.getCatalogBlock(dirPath, 2);
        //读取要删除路径的FCB
        Catalog catalog = disk.readCatalog(catalogBlock);
        //物理层删除
        disk.rmdir(parent,catalog);
        //视图层更新
        mainController.removeTreeItem(catalog);
    }

    /**
     * 读取已打开文件
     *
     * @param openedFile
     * @param length
     * @return
     */
    public  byte[] read(OpenedFile openedFile, int length) throws Exception {
        if (openedFile.getOpType() != OpenedFile.OP_TYPE_READ &&
                openedFile.getOpType() != OpenedFile.OP_TYPE_READ_WRITE &&
                openedFile.getOpType()!=OpenedFile.OP_TYPE_RUN) {
            throw new Exception("文件不处于读或运行模式,不能读取");
        }
        return disk.read(openedFile,length);
    }

    /**
     * 写入已打开文件中
     *
     * @param openedFile
     * @param buffer
     * @param length
     * @throws Exception
     */
    public void write(OpenedFile openedFile, byte[] buffer, int length) throws Exception {
        if (openedFile.getOpType() != OpenedFile.OP_TYPE_WRITE&&openedFile.getOpType()!=OpenedFile.OP_TYPE_READ_WRITE) {
            throw new Exception("文件只读，不可写入");
        }
        Pointer pointer = openedFile.getWritePointer();
        Catalog catalog = openedFile.getCatalog();
        pointer.setBlockNo(catalog.getStartBlock());
        int writtenBytes = 0;
        while (writtenBytes != length) {
            if (pointer.getAddress() == OsConstant.DISK_BLOCK_SIZE) {
                pointer.setBlockNo(disk.getNextBlock(pointer.getBlockNo()));
            }
            if (pointer.getBlockNo() == -1) {
                //申请空间,注意修改文件分配表,修改文件长度
                int blockNo = disk.firstFreeBlock();
                if (blockNo == -1) {
                    throw new Exception("磁盘空间不足！");
                }
                //之前是空文件，不占用磁盘空间
                if (catalog.getStartBlock() == -1) {
                    catalog.setStartBlock(blockNo);
                }
                int last = disk.getLastBlock(catalog.getStartBlock());
                //修改文件分配表
                setNextBlock(blockNo, -1);
                //修改写指针位置
                pointer.setBlockNo(blockNo);
                pointer.setAddress(0);
                //修改文件长度
                catalog.setFileLength(catalog.getFileLength() + 1);
                writeCatalog(catalog);
            }
            disk.seek(pointer.getBlockNo() * OsConstant.DISK_BLOCK_SIZE + pointer.getAddress());
            disk.write(buffer[writtenBytes++]);
            pointer.setAddress(pointer.getAddress() + 1);
        }
        //写入结束符
        disk.write('#');
    }

    /**
     * 修改文件分配表指向的下一个磁盘块
     *
     * @param i
     * @param nextBlock
     * @throws IOException
     */
    private void setNextBlock(int i, int nextBlock) throws IOException {
        //将第i位设置位nextBlock
        disk.setNextBlock(i,nextBlock);
        //更新分区表视图
        mainController.updateFatView();
    }

    /**
     * 获取文件对应的打开文件信息
     *
     * @param filePath
     * @return
     * @throws Exception
     */
    private OpenedFile getOpenedFile(String filePath) throws Exception {
        OpenedFile file = null;
        for (OpenedFile openedFile : openedFiles) {
            if (openedFile.getFilePath().equals(filePath)) {
                file = openedFile;
                break;
            }
        }
        if (file == null) {
            throw new Exception("文件未打开！");
        }
        return file;
    }

    /**
     * 写入目录
     *
     * @param catalog
     * @throws IOException
     */
    private void writeCatalog(Catalog catalog) throws IOException {
        //物理层写入
        disk.writeCatalog(catalog);
        //视图层更新
        mainController.updateTreeItem(catalog);
    }

    /**
     * 判断是否存在文件夹
     *
     * @param dirName  文件夹名字
     * @param location 位置
     * @return boolean
     * @throws Exception 异常
     */
    public boolean existsDir(String dirName,String location) throws Exception {

        int catalogBlock = disk.getCatalogBlock(location+"/"+dirName, 2);
        Catalog catalog = disk.readCatalog(catalogBlock);
        if(!catalog.isDirectory()) {
            throw new Exception(catalog.getName()+"不是文件夹");
        }
        return true;
    }

    /**
     * 查看文件是否处于打开状态
     *
     * @param c c
     * @return boolean
     * @throws Exception 异常
     */
    public boolean isOpen(Catalog c) {
        for(OpenedFile openedFile:openedFiles){
        if (openedFile.getCatalog().equals(c)){
                return false;
            }
        }
        return true;
    }

    /**
     * 设置主控制器
     *
     * @param mainController 主控制器
     */
    public void setMainController(MainController mainController) {
        this.mainController = mainController;
    }

    public void gcc(String srcFilePath, String desFilePath) throws Exception {
        if(desFilePath.length() <= 0 || desFilePath.charAt(desFilePath.length()-1) != 'e'){
            desFilePath = desFilePath+"e";
        }
        OpenedFile srcFile=null,desFile=null;
        try {
            srcFile = open(srcFilePath, OpenedFile.OP_TYPE_READ);
            //新文件设置为可执行文件
            create(desFilePath, Byte.parseByte("00010000",2));
            desFile = open(desFilePath, OpenedFile.OP_TYPE_WRITE);
            //读取源码
            byte[] content = read(srcFile, -1);
            String code = new String(content);
            //消除结束符
            code = code.split("#")[0];
            //源码切割
            String[] sentences = code.split(";");
            //语句翻译
            byte[] machineCode = Software.cpu.getInstruction(sentences);
            write(desFile, machineCode, machineCode.length);
        }catch (Exception e){
            throw e;
        }finally {
            if (srcFile!=null) {
                close(srcFile);
            }
            if (desFile!=null){
                close(desFile);
            }
        }
    }
}
