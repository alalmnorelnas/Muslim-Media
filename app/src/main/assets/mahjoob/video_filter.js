(function () {
	'use strict';

	var bridgeName = 'mahjoobVideo';
	var styleId = 'mahjoob-video-cover-style';
	var maxSide = 480;
	var sampleIntervalMs = 500;
	var minimumClearMs = 1500;
	var replyTimeoutMs = 2500;
	var nextId = 1;
	var states = new WeakMap();
	var pending = new Map();

	function installStyle() {
		if (!document.documentElement || document.getElementById(styleId)) {
			return Boolean(document.getElementById(styleId));
		}
		var style = document.createElement('style');
		style.id = styleId;
		style.textContent = 'video:not(.mahjoob-safe-video){visibility:hidden!important;}';
		document.documentElement.appendChild(style);
		return true;
	}

	function getState(video) {
		var state = states.get(video);
		if (!state) {
			state = {
				canvas: document.createElement('canvas'),
				context: null,
				originalVisibility: video.style.getPropertyValue('visibility'),
				originalVisibilityPriority: video.style.getPropertyPriority('visibility'),
				allowCount: 0,
				lastBlockAt: Date.now(),
				lastSampleAt: 0,
				epoch: 0,
				pending: false,
				permanent: false
			};
			state.context = state.canvas.getContext('2d', { alpha: false });
			states.set(video, state);
		}
		return state;
	}

	function cover(video, state, permanent) {
		state.epoch++;
		state.allowCount = 0;
		state.pending = false;
		state.lastBlockAt = Date.now();
		if (permanent) {
			state.permanent = true;
		}
		video.style.setProperty('visibility', 'hidden', 'important');
		video.classList.remove('mahjoob-safe-video');
	}

	function handleReply(event) {
		var response;
		try {
			response = JSON.parse(event.data);
		} catch (error) {
			pending.forEach(function (entry) {
				cover(entry.video, entry.state, true);
			});
			pending.clear();
			return;
		}
		var entry = pending.get(response.id);
		if (!entry) {
			return;
		}
		pending.delete(response.id);
		if (entry.state.epoch !== entry.epoch) {
			return;
		}
		entry.state.pending = false;
		if (response.decision !== 'allow') {
			cover(entry.video, entry.state, false);
			return;
		}
		entry.state.allowCount++;
		if (entry.state.allowCount >= 2 && Date.now() - entry.state.lastBlockAt >= minimumClearMs) {
			entry.video.classList.add('mahjoob-safe-video');
			if (entry.state.originalVisibility) {
				entry.video.style.setProperty('visibility', entry.state.originalVisibility,
						entry.state.originalVisibilityPriority);
			} else {
				entry.video.style.removeProperty('visibility');
			}
		}
	}

	function sample(video) {
		var state = getState(video);
		if (state.permanent || video.seeking || video.paused || video.ended) {
			return;
		}
		if (video.readyState < 2 || video.videoWidth < 1 || video.videoHeight < 1) {
			cover(video, state, false);
			return;
		}
		if (state.pending) {
			return;
		}
		var now = Date.now();
		if (now - state.lastSampleAt < sampleIntervalMs) {
			return;
		}
		state.lastSampleAt = now;
		var channel = window[bridgeName];
		if (!channel || typeof channel.postMessage !== 'function' || !state.context) {
			cover(video, state, true);
			return;
		}

		var scale = Math.min(1, maxSide / Math.max(video.videoWidth, video.videoHeight));
		var width = Math.max(1, Math.round(video.videoWidth * scale));
		var height = Math.max(1, Math.round(video.videoHeight * scale));
		var frame;
		try {
			state.canvas.width = width;
			state.canvas.height = height;
			state.context.drawImage(video, 0, 0, width, height);
			var dataUrl = state.canvas.toDataURL('image/jpeg', 0.55);
			if (dataUrl.indexOf('data:image/jpeg;base64,') !== 0) {
				cover(video, state, true);
				return;
			}
			frame = dataUrl.substring('data:image/jpeg;base64,'.length);
		} catch (error) {
			cover(video, state, true);
			return;
		}

		var id = nextId++;
		var entry = { video: video, state: state, epoch: state.epoch };
		state.pending = true;
		pending.set(id, entry);
		try {
			channel.postMessage(JSON.stringify({ type: 'frame', id: id, jpeg: frame }));
		} catch (error) {
			pending.delete(id);
			cover(video, state, true);
			return;
		}
		setTimeout(function () {
			if (pending.get(id) === entry) {
				pending.delete(id);
				cover(video, state, false);
			}
		}, replyTimeoutMs);
	}

	function scan() {
		if (!installStyle()) {
			return;
		}
		var videos = document.querySelectorAll('video');
		for (var index = 0; index < videos.length; index++) {
			var video = videos[index];
			getState(video);
			if (video.seeking) {
				cover(video, states.get(video), false);
			} else if (!video.paused && !video.ended) {
				sample(video);
			}
		}
	}

	function bind() {
		installStyle();
		var channel = window[bridgeName];
		if (channel && typeof channel.addEventListener === 'function') {
			channel.addEventListener('message', handleReply);
		} else if (channel) {
			channel.onmessage = handleReply;
		}
		document.addEventListener('seeking', function (event) {
			var video = event.target;
			if (video && video.tagName === 'VIDEO') {
				cover(video, getState(video), false);
			}
		}, true);
		var observer = new MutationObserver(scan);
		observer.observe(document.documentElement || document, { childList: true, subtree: true });
		scan();
		setInterval(scan, sampleIntervalMs);
	}

	if (document.documentElement) {
		bind();
	} else {
		var documentObserver = new MutationObserver(function () {
			if (document.documentElement) {
				documentObserver.disconnect();
				bind();
			}
		});
		documentObserver.observe(document, { childList: true, subtree: true });
	}
})();