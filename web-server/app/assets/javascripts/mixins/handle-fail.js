define(['jquery'], function($) {
  var HandleFailMixin = {
    getInitialState: function() {
      return {postStatus : ""};
    },
    sendPostRequest: function(url, data) {
      return this.sendRequest("POST", url, data);
    },
    sendPutRequest: function(url, data) {
      return this.sendRequest("PUT", url, data);
    },
    sendRequest: function(type, url, data) {
      this.makeAjaxRequest(type, url, data)
        .success(this.onSuccess.bind(this))
        .fail(this.onFail.bind(this));
    },
    makeAjaxRequest: function(type, url, data) {
      return $.ajax({
        type: type,
        url: url,
        dataType: 'json',
        data: JSON.stringify(data),
        contentType: "application/json"
      });
    },
    onFail: function(data) {
      var res = JSON.parse(data.responseText);
      this.setState({postStatus: res.errorMsg});
    }
  };

  return HandleFailMixin;
});
