/**
 * 
 */

angular.module('adam4017View',[])
.controller('adam4017Ctrl', ['InstConfigService', '$scope', function($config, $scope) {
	$scope.channels=[0, 1, 2, 3, 4, 5, 6, 7];
	var index = 0;
	var paramList = [];

	if(isEmpty($config.param)){
		console.log("config.param is empty...");		
		$scope.param.addr = '01';
		$scope.param.ch=[];
		for(var i=0;i<8;i++){
			$scope.param.ch.push({});
		}
		paramList.push($scope.param);
	}else{
		$scope.param=$config.param[0];
		paramList = $config.param;
	}
		
	$scope.moreDevice = function(){
		index++;
		$scope.param = {};
		$scope.param.addr = '0'+(index+1);
		$scope.param.ch=[];
		for(var i=0;i<8;i++){
			$scope.param.ch.push({});
		}
		paramList.push($scope.param);		
	}
	
	$scope.prevDevice = function(){
		if(index>0){
			index--;
			$scope.param = paramList[index];
		}			
	}
	
	$scope.nextDevice = function(){
		if(index+1 < paramList.length){
			index++;
			$scope.param = paramList[index];
		}
	}
	
	$config.summary = function(){
		var desc="";
		for(var idx=0;idx < paramList.length; idx++){
			desc += "<br/>位址:" + paramList[idx].addr;
			for(var i=0;i<8;i++){
				if(paramList[idx].ch[i].enable){
					desc += "<br/>CH"+i +":啟用";			
					desc += "<br/><strong>測項:" + paramList[idx].ch[i].mt + "</strong>";
					desc += "<br/>最大值:" + paramList[idx].ch[i].max;
					desc += "<br/>測項最大值:" + paramList[idx].ch[i].mtMax;
					desc += "<br/>最小值:" + paramList[idx].ch[i].min;
					desc += "<br/>測項最小值:" + paramList[idx].ch[i].mtMin;						
				}	
			}
		}
		
		return desc;
	}
	
	$config.validate=function(){
		for(var idx=0;idx < paramList.length; idx++){
			if(paramList[idx].addr.length == 0){
				alert(idx +": 位址是空的!");
				return false;
			}

			for(var i=0;i<8;i++){
				if(paramList[idx].ch[i].enable){
					try{
						if(paramList[idx].ch[i].max.length == 0){
							alert("請指定最大值");
							return false;
						}
						if(paramList[idx].ch[i].mtMax.length == 0){
							alert("請指定測項最大值");
							return false;
						}
						if(paramList[idx].ch[i].min.length == 0){
							alert("請指定最小值");
							return false;
						}
						if(paramList[idx].ch[i].mtMin.length == 0){
							alert("請指定測項最小值");
							return false;
						}				
					
						paramList[idx].ch[i].max = parseFloat(paramList[idx].ch[i].max);
						paramList[idx].ch[i].mtMax = parseFloat(paramList[idx].ch[i].mtMax);
						paramList[idx].ch[i].min = parseFloat(paramList[idx].ch[i].min);
						paramList[idx].ch[i].mtMin = parseFloat(paramList[idx].ch[i].mtMin);
					}catch(ex){
						alert(ex.toString());
						return false;
					}
				}else
					paramList[idx].ch[i].enable = false;
			}
		}
		
		//copy back
		$config.param = paramList;
		return true;
	}
}]);