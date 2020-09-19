package com.mg.common.upload.service;

import com.mg.common.upload.vo.UploadBase64;
import com.mg.common.upload.vo.UploadBean;
import com.mg.common.utils.Base64Util;
import com.mg.common.utils.FtpUtils;
import com.mg.framework.sys.PropertyConfigurer;
import com.mg.framework.utils.UserHolder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;

/**
 * 图片上传公用类
 * Created by liukefu on 2016/12/17.
 */
@Service
public class UploadServiceImpl implements UploadService {
    private static Logger logger = LoggerFactory.getLogger(UploadServiceImpl.class);
    private static final char separator = '/';

    public List<UploadBean> upload(MultipartHttpServletRequest mulRequest, String userPath) {
        logger.debug("上传文件开始...");
        List<UploadBean> list = new ArrayList<>();
        Map<String, MultipartFile> fileMap = mulRequest.getFileMap();
        Iterator<String> it = fileMap.keySet().iterator();
        logger.debug("文件个数："+fileMap.size());
        logger.debug("保存路径："+userPath);
        try{
            while (it.hasNext()) {
                UploadBean uploadBean = new UploadBean();
                uploadBean.setUserPath(userPath);

                //保存文件到服务器
                File file = getFileSavePath(uploadBean);
                String key = it.next();
                uploadBean.setKey(key);
                MultipartFile multipartFile = fileMap.get(key);
                if (!multipartFile.isEmpty()) {
                    ftpUpload(uploadBean,file,multipartFile);
                }

                //返回文件路径
                uploadBean.setRelativePath(uploadBean.getRelativePath() + uploadBean.getFileName());

                logger.debug("fileName："+uploadBean.getFileName());
                logger.debug("relativePath："+uploadBean.getRelativePath());
                logger.debug("path："+uploadBean.getPath());

                list.add(uploadBean);
            }
        }catch (Exception ex){
            ex.printStackTrace();
        }

        return list;
    }

    public Map<String,UploadBean> uploadForMap(MultipartHttpServletRequest mulRequest, String userPath) {

        Map<String,UploadBean> map = new HashMap<>();
        Map<String, MultipartFile> fileMap = mulRequest.getFileMap();
        Iterator<String> it = fileMap.keySet().iterator();
        while (it.hasNext()) {
            UploadBean uploadBean = new UploadBean();
            uploadBean.setUserPath(userPath);

            //保存文件到服务器
            File file = getFileSavePath(uploadBean);
            String key = it.next();
            uploadBean.setKey(key);
            MultipartFile multipartFile = fileMap.get(key);
            if (!multipartFile.isEmpty()) {
                ftpUpload(uploadBean,file,multipartFile);
            }

            //返回文件路径
            uploadBean.setRelativePath(uploadBean.getRelativePath() + uploadBean.getFileName());

            map.put(key,uploadBean);
        }

        return map;
    }

    private String getNewFileName(File file, MultipartFile item) {
        StringBuffer sb = new StringBuffer();
        String str = String.valueOf(Math.round(Math.random() * 1000000));
        sb.append("mg").append(new Date().getTime()).append(str);
        sb.append(item.getOriginalFilename().substring(item.getOriginalFilename().lastIndexOf(".")));
        return sb.toString();
    }

    public File getFileSavePath(UploadBean uploadBean) {
        String instanceId = UserHolder.getLoginUserTenantId();
        String rootPath = "mg-static";
        if (StringUtils.isNotBlank(instanceId)) {
            rootPath = instanceId;
        }
        String savePath = separator + rootPath + separator;

        if (StringUtils.isNotBlank(uploadBean.getUserPath())) {
            String today = DateFormatUtils.format(new Date(), "yyyyMMdd");
            savePath = savePath + uploadBean.getUserPath() + separator + today + separator;
        }else{
            String today = DateFormatUtils.format(new Date(), "yyyyMMdd");
            savePath = savePath + today + separator;
        }
        uploadBean.setRelativePath(savePath);
        savePath = PropertyConfigurer.getContextProperty("temppath") + savePath;
        File file = new File(savePath);
        file.mkdirs();

        return file;
    }

    public boolean removeFile(String path) {

        String home = (String) PropertyConfigurer.getContextProperty("temppath");
        File file = new File(home + path);
        file.deleteOnExit();
        return true;
    }

    public List<UploadBean> uploadBase64(UploadBase64 uploadBase64) {
        List<UploadBean> list = new ArrayList<>();
        UploadBean uploadBean = new UploadBean();
        uploadBean.setUserPath(uploadBase64.getUserPath());

        //保存文件到服务器
        File file = getFileSavePath(uploadBean);
        String key = uploadBase64.getKey();
        uploadBean.setKey(key);

        String str = String.valueOf(Math.round(Math.random() * 1000000));
        String name = new StringBuilder("mg").append(new Date().getTime()).append(str).append(".jpg").toString();

        StringBuffer sb = new StringBuffer(file.getPath()).append(separator).append(name);
        logger.info("上传文件:"+sb.toString());
        try {
            File f = new File(sb.toString());
            logger.info("设置上传文件权限");
            f.setReadable(true,false);
            f.setWritable(true,false);
            f.setExecutable(true,false);

            boolean b = Base64Util.Base64ToImage(uploadBase64.getImgStr(),f.getAbsolutePath());
            logger.info("base64转文件格式成功标志："+b);
            logger.info("absolutePath："+f.getAbsolutePath());

            String enableFtp = PropertyConfigurer.getConfig("ftp.enable");
            if("1".equals(enableFtp)){
                logger.info("启用了FTP上传");
                FtpUtils ftp =new FtpUtils();
                ftp.uploadFile(uploadBean.getRelativePath(), name, new FileInputStream(f));
            }

            uploadBean.setFileName(f.getName());
            uploadBean.setPath(f.getPath());
            logger.info("relativePath："+uploadBean.getRelativePath());
            logger.info("file path : {}", file.getPath());
        } catch (Exception e) {
            e.printStackTrace();
        }

        //返回文件路径
        uploadBean.setRelativePath(uploadBean.getRelativePath() + uploadBean.getFileName());

        list.add(uploadBean);

        return list;
    }

    public void ftpUpload(UploadBean uploadBean,File file,MultipartFile multipartFile){
        try {
            String enableFtp = PropertyConfigurer.getConfig("ftp.enable");
            try {
                if("1".equals(enableFtp)){
                    String name = getNewFileName(file, multipartFile);
                    uploadBean.setFileName(name);
                    logger.info("启用了FTP上传");
                    FtpUtils ftp =new FtpUtils();
                    ftp.uploadFile(uploadBean.getRelativePath(), name, multipartFile.getInputStream());
                }else{
                    logger.info("未启用了FTP上传");
                    File f = new File(uploadBean.getRelativePath()+getNewFileName(file, multipartFile));
                    logger.info("设置上传文件权限");
                    f.setReadable(true,false);
                    f.setWritable(true,false);
                    f.setExecutable(true,false);
                    multipartFile.transferTo(f);
                }
            }catch (Exception e){
                e.printStackTrace();
            }

            //uploadBean.setPath(f.getPath());
            logger.info("file path : {}", file.getPath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
