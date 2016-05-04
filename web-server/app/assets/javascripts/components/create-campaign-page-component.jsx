define(function(require) {

  var _ = require('underscore'),
      React = require('react'),
      AffectedDevices = require('./devices/affected-devices'),
      showModel = require('../mixins/show-model'),
      db = require('stores/db'),
      CreateUpdate = require('components/create-update');

  var ShowPackageComponent = React.createClass({
    contextTypes: {
      router: React.PropTypes.func
    },
    render: function() {
      var params = this.context.router.getCurrentParams();
      return (
        <div>
          <h1>
            Packages &gt; {params.name}-{params.version} &gt; New Update
          </h1>
          <AffectedDevices AffectedDevices={db.affectedDevices} />
          <CreateUpdate packageName={params.name} packageVersion={params.version}/>
        </div>
      );
    }
  });

  return ShowPackageComponent;
});
