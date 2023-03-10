/*
 * (C) Copyright 2014 Kurento (http://kurento.org/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

const ws = new WebSocket('wss://' + location.host + '/call');
let videoInput;
let videoOutput;
let webRtcPeer;
let response;
let callerMessage;
let from;
let hasWebcam;

let registerName = null;
let registerState = null;
const NOT_REGISTERED = 0;
const REGISTERING = 1;
const REGISTERED = 2;

window.onload = () => {
	console = new Console();
	setRegisterState(NOT_REGISTERED);
	let drag = new Draggabilly(document.getElementById('videoSmall'));
	videoInput = document.getElementById('videoInput');
	videoOutput = document.getElementById('videoOutput');
	document.getElementById('name').focus();
	navigator.getUserMedia({ video: true, audio: false }, () => hasWebcam = true, () => hasWebcam = false);
}

window.onbeforeunload = () => {
	ws.close();
}

function setRegisterState(nextState)
{
	switch (nextState)
	{
		case NOT_REGISTERED:
			enableButton('#register', 'register()');
			enableInput("#name")
			setCallState(NO_CALL);
			break;
		case REGISTERING:
			disableButton('#register');
			disableInput("#name")
			break;
		case REGISTERED:
			disableButton('#register');
			$('#register').css("display", "none")
			disableInput("#name")
			setCallState(NO_CALL);
			break;
		default:
			return;
	}
	registerState = nextState;
}

let callState = null;
const NO_CALL = 0;
const PROCESSING_CALL = 1;
const IN_CALL = 2;

function setCallState(nextState)
{
	switch (nextState) 
	{
		case NO_CALL:
			enableInput('#peer')
			enableButton('#call', 'call()');
			disableButton('#terminate');
			disableButton('#play');
			break;
		case PROCESSING_CALL:
			disableButton('#call');
			disableButton('#terminate');
			disableButton('#play');
			disableInput('#peer')
			break;
		case IN_CALL:
			disableButton('#call');
			disableButton('#play');
			disableInput("#peer")
			enableButton('#terminate', 'stop()');
			break;
		default:
			return;
	}
	callState = nextState;
}

ws.onmessage = function (message)
{
	let parsedMessage = JSON.parse(message.data);
	console.info('Received message: ' + message.data);

	switch (parsedMessage.id)
	{
		case 'registerResponse':
			registerResponse(parsedMessage);
			break;
		case 'callResponse':
			callResponse(parsedMessage);
			break;
		case 'incomingCall':
			incomingCall(parsedMessage);
			break;
		case 'startCommunication':
			startCommunication(parsedMessage);
			break;
		case 'stopCommunication':
			alert('Communication ended by remote peer');
			console.info('Communication ended by remote peer');
			stop(true);
			break;
		case 'iceCandidate':
			webRtcPeer.addIceCandidate(parsedMessage.candidate, (error) => {
				if (error)
					return console.error('Error adding candidate: ' + error);
			});
			break;
		default:
			console.error('Unrecognized message', parsedMessage);
	}
}

function registerResponse(message)
{
	if (message.response == 'accepted')
	{
		setRegisterState(REGISTERED);
	}
	else 
	{
		setRegisterState(NOT_REGISTERED);
		let errorMessage = message.message ? message.message : 'Unknown reason for register rejection.';
		console.log(errorMessage);
		alert('Error registering user. See console for further information.');
	}
}

function callResponse(message)
{
	hideSpinner(videoInput, videoOutput)
	if (message.response != 'accepted') 
	{
		let errorMessage = message.response ? message.response : 'Unknown reason for call rejection.';
		alert(errorMessage);
		console.log(errorMessage);
		stop();
	}
	else 
	{
		setCallState(IN_CALL);
		disableInput('#peer')
		webRtcPeer.processAnswer(message.sdpAnswer, (error) => {
			if (error)
				return console.error(error);
		});
	}
}

function startCommunication(message)
{
	hideSpinner(videoOutput)

	setCallState(IN_CALL);
	webRtcPeer.processAnswer(message.sdpAnswer, (error) => {
		if (error)
			return console.error(error);
	});
}

function incomingCall(message)
{
	// If bussy just reject without disturbing user
	if (callState != NO_CALL)
	{
		const response = {
			id: 'incomingCallResponse',
			from: message.from,
			callResponse: 'reject',
			message: 'bussy'
		};
		return sendMessage(response);
	}

	setCallState(PROCESSING_CALL);
	if (confirm(`User ${message.from} is calling. Do you accept the call?`))
	{
		showSpinner(videoInput, videoOutput);

		from = message.from;

		const options = {
			localVideo: videoInput,
			remoteVideo: videoOutput,
			mediaConstraints:
			{
				audio: true,
				video: message.isVideoCall
			},
			onicecandidate: onIceCandidate,
			onerror: onError
		}

		$('#peer').attr("value", message.from)
		webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options, (error) => {
			if (error)
				return console.error(error);
			webRtcPeer.generateOffer(onOfferIncomingCall);
		});
	}
	else
	{
		const response = {
			id: 'incomingCallResponse',
			from: message.from,
			callResponse: 'reject',
			message: 'user declined'
		};
		sendMessage(response);
		stop();
	}
	hideSpinner(videoInput);
}

function onOfferIncomingCall(error, offerSdp)
{
	if (error)
		return console.error("Error generating the offer");
	let response = {
		id: 'incomingCallResponse',
		from: from,
		callResponse: 'accept',
		sdpOffer: offerSdp,
	};
	sendMessage(response);
}

function register()
{
	let name = document.getElementById('name').value;
	if (name == '')
	{
		window.alert('You must insert your user name');
		return;
	}
	setRegisterState(REGISTERING);

	const message = {
		id: 'register',
		name: name
	};
	sendMessage(message);
	document.getElementById('peer').focus();
}

function call()
{
	if (document.getElementById('peer').value == '')
	{
		window.alert('You must specify the peer name');
		return;
	}
	setCallState(PROCESSING_CALL);
	showSpinner(videoInput, videoOutput);

	let options = {
		localVideo: videoInput,
		remoteVideo: videoOutput,
		mediaConstraints:
		{
			audio: true,
			video: hasWebcam
		},
		onicecandidate: onIceCandidate,
		onerror: onError
	}

	webRtcPeer = new kurentoUtils.WebRtcPeer.WebRtcPeerSendrecv(options,
		function (error) {
			if (error) {
				return console.error(error);
			}
			webRtcPeer.generateOffer(onOfferCall);
		});
}

function onOfferCall(error, offerSdp)
{
	if (error)
		return console.error('Error generating the offer ', error);

	console.log(`SDP OFFER =`, offerSdp)
	console.log('Invoking SDP offer callback function');
	let message = {
		id: 'call',
		from: document.getElementById('name').value,
		to: document.getElementById('peer').value,
		sdpOffer: offerSdp,
		isVideoCall: hasWebcam
	};
	sendMessage(message);
}

function stop(message)
{
	setCallState(NO_CALL);
	if (webRtcPeer)
	{
		webRtcPeer.dispose();
		webRtcPeer = null;

		if (!message) {
			let message = {
				id: 'stop'
			}
			sendMessage(message);
		}
	}
	hideSpinner(videoInput, videoOutput);
}

function onError()
{
	setCallState(NO_CALL);
}

function onIceCandidate(candidate)
{
	console.log("Local candidate" + JSON.stringify(candidate));

	let message = {
		id: 'onIceCandidate',
		candidate: candidate
	};
	sendMessage(message);
}

function sendMessage(message)
{
	let jsonMessage = JSON.stringify(message);
	console.log('Sending message: ' + jsonMessage);
	ws.send(jsonMessage);
}

function showSpinner()
{
	for (let i = 0; i < arguments.length; i++)
	{
		arguments[i].poster = './img/transparent-1px.png';
		arguments[i].style.background = 'center transparent url("./img/spinner.gif") no-repeat';
	}
}

function hideSpinner()
{
	for (let i = 0; i < arguments.length; i++)
	{
		arguments[i].src = '';
		arguments[i].poster = './img/webrtc.png';
		arguments[i].style.background = '';
	}
}

function disableButton(id)
{
	$(id).attr('disabled', true);
	$(id).removeAttr('onclick');
	$(id).addClass('not-usable-button');
}

function enableButton(id, functionName)
{
	$(id).attr('disabled', false);
	$(id).attr('onclick', functionName);
	$(id).removeClass('not-usable-button');
}

function disableInput(id)
{
	$(id).attr("readonly", true)
	$(id).addClass('not-usable-input');
}

function enableInput(id)
{
	$(id).attr("readonly", false)
	$(id).removeClass('not-usable-input');
}

/**
 * Lightbox utility (to display media pipeline image in a modal dialog)
 */
$(document).delegate('*[data-toggle="lightbox"]', 'click', (event) => {
	event.preventDefault();
	$(this).ekkoLightbox();
});
