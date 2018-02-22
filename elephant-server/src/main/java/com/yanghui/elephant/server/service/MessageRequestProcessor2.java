package com.yanghui.elephant.server.service;

import java.util.Date;
import java.util.Map;

import lombok.extern.log4j.Log4j2;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import io.netty.channel.ChannelHandlerContext;

import com.alibaba.fastjson.JSON;
import com.yanghui.elephant.common.constant.ResponseCode;
import com.yanghui.elephant.common.constant.LocalTransactionState;
import com.yanghui.elephant.common.constant.MessageStatus;
import com.yanghui.elephant.common.constant.SendStatus;
import com.yanghui.elephant.common.message.Message;
import com.yanghui.elephant.mq.producer.ProducerService;
import com.yanghui.elephant.remoting.RequestProcessor;
import com.yanghui.elephant.remoting.procotol.RemotingCommand;
import com.yanghui.elephant.store.entity.MessageEntity;
import com.yanghui.elephant.store.mapper.MessageEntityMapper;

@Service
@Log4j2
public class MessageRequestProcessor implements RequestProcessor {
	
	@Autowired
	private MessageEntityMapper messageEntityMapper;
	@Autowired
	private ProducerService producerService;
	
	@Override
	public RemotingCommand processRequest(ChannelHandlerContext ctx,RemotingCommand request) {
		log.info("处理消息：{}",request);
		switch (request.getMessageCode()) {
		case NORMAL_MESSAGE:
			RemotingCommand response = handleMessage(request,false);
			if(response.getCode() == ResponseCode.SUCCESS){
				try {
					sendMessageToMQ(request.getMessage());
				} catch (Exception e) {
					response.setCode(ResponseCode.SEND_MQ_FAIL);
					log.info("发送mq失败：{}",e);
				}
			}
			return response;
		case TRANSACTION_PRE_MESSAGE:
			return handleMessage(request,true);
		case TRANSACTION_END_MESSAGE:
			handleTransactionEndAndCheckMessage(request);
			break;
		case TRANSACTION_CHECK_MESSAGE:
			handleTransactionEndAndCheckMessage(request);
			break;
		default:
			break;
		}
		return null;
	}
	
	private RemotingCommand handleMessage(RemotingCommand request,boolean isTransation){
		RemotingCommand response = RemotingCommand.buildResposeCmd(ResponseCode.SERVER_FAIL, request.getUnique());
		MessageEntity entity = this.bulidMessageEntity(request,isTransation);
		int code = saveOrUpdateMassageEntity(entity,true);
		response.setCode(code);
		return response;
	}
	
	@SuppressWarnings("unchecked")
	private void handleTransactionEndAndCheckMessage(RemotingCommand request){
		try {
			String messageId = request.getMessage().getMessageId();
			MessageEntity entity = new MessageEntity();
			entity.setMessageId(messageId);
			entity.setStatus(buildStatus(request.getLocalTransactionState()));
			entity.setRemark(request.getRemark());
			saveOrUpdateMassageEntity(entity, false);
			if(entity.getStatus() == MessageStatus.CONFIRMED.getStatus()){
				MessageEntity select = new MessageEntity();
				select.setMessageId(messageId);
				MessageEntity find = this.messageEntityMapper.selectOne(select);
				Message message = new Message();
				message.setMessageId(messageId);
				message.setBody(find.getBody());
				message.setDestination(find.getDestination());
				if(!StringUtils.isEmpty(find.getProperties())){
					message.setProperties(JSON.parseObject(find.getProperties(), Map.class));
				}
				sendMessageToMQ(message);
			}
		} catch (Exception e) {
			log.error("handleTransactionEndAndCheckMessage 发生异常：{}",e);
		}
	}
	
	private void sendMessageToMQ(Message message){
		this.producerService.sendMessage(message);
		MessageEntity update = new MessageEntity();
		update.setMessageId(message.getMessageId());
		update.setSendStatus(SendStatus.ALREADY_SEND.getStatus());
		update.setUpdateTime(new Date());
		this.messageEntityMapper.updateByMessageId(update);
	}
	
	private int saveOrUpdateMassageEntity(MessageEntity entity,boolean isInsert){
		try {
			MessageEntity select = new MessageEntity();
			select.setMessageId(entity.getMessageId());
			MessageEntity find = this.messageEntityMapper.selectOne(select);
			if(find == null){
				if(isInsert)this.messageEntityMapper.insert(entity);
			}else {
				MessageEntity update = new MessageEntity();
				update.setId(find.getId());
				update.setStatus(entity.getStatus());
				update.setRemark(entity.getRemark());
				update.setUpdateTime(new Date());
				this.messageEntityMapper.updateById(update);
			}
			return ResponseCode.SUCCESS;
		} catch (Exception e) {
			log.error("保存数据库失败：{}",e);
			return ResponseCode.FUSH_DB_FAIL;
		}
	}
	
	private MessageEntity bulidMessageEntity(RemotingCommand request,boolean isTransaction){
		Message message = request.getMessage();
		MessageEntity entity = new MessageEntity();
		entity.setBody(message.getBody());
		entity.setCreateTime(new Date());
		entity.setDestination(message.getDestination());
		entity.setGroup(request.getGroup());
		entity.setMessageId(message.getMessageId());
		if(!CollectionUtils.isEmpty(message.getProperties())){
			entity.setProperties(JSON.toJSONString(message.getProperties()));
		}
		entity.setSendStatus(SendStatus.WAIT_SEND.getStatus());
		entity.setTransaction(isTransaction);
		entity.setRemark(request.getRemark());
		entity.setUpdateTime(entity.getCreateTime());
		if(!entity.getTransaction()){
			entity.setStatus(MessageStatus.CONFIRMED.getStatus());
			return entity;
		}
		entity.setStatus(MessageStatus.CONFIRMING.getStatus());
		return entity;
	}
	
	private int buildStatus(LocalTransactionState localTransactionState){
		switch (localTransactionState) {
		case COMMIT_MESSAGE:
			return MessageStatus.CONFIRMED.getStatus();
		case ROLLBACK_MESSAGE:
			return MessageStatus.ROLLBACK.getStatus();
		case UNKNOW:
			return MessageStatus.CONFIRMING.getStatus();
		default:
			return MessageStatus.CONFIRMING.getStatus();
		}
	}
}
